package com.lcl.yunpicturebackend.manager.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.config.AiAssistantProperties;
import com.lcl.yunpicturebackend.domain.dto.ai.AiBatchEditPlan;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationConfirmRequest;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationPlan;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationPrepareRequest;
import com.lcl.yunpicturebackend.domain.dto.ai.AiUrlImportPlan;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureEditByBatchRequest;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureUploadRequest;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.AiOperationItemResultVO;
import com.lcl.yunpicturebackend.domain.vo.AiOperationPrepareVO;
import com.lcl.yunpicturebackend.domain.vo.AiOperationResultVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * AI 助手写操作确认门（T27.5）：后端持有待执行计划 + 原子确认。
 * <p>
 * prepare 只存计划零写库（轻量形状校验后存 Redis，短 TTL，值含 userId/chatId/digest/规范化计划）；
 * confirm 用 Lua 把"用户/会话/摘要全匹配 + 计划与摘要一致 + 单次删除消费"做成一个原子脚本，
 * 消费成功后用服务端保存的原始计划执行——批量编辑与 URL 入库分别走既有
 * {@code PictureService#editPictureByBatch} / {@code PictureService#uploadPicture}，
 * RBAC、额度与 URL 安全校验全部留在既有 service，确认不绕过它们。
 * <p>
 * digest 是规范化计划 JSON 的 SHA-1 hex：Lua 内用 {@code redis.sha1hex} 复核
 * "计划与摘要一致"（Redis 内置没有 sha256），保证存储值被外部改动后 fail-closed 且不消费；
 * 确认请求回传同一 digest，把"用户确认时看到的计划"钉死到"实际执行的计划"。
 * <p>
 * 失败语义：不存在/过期/已消费/别人的 operationId/会话不匹配/摘要被篡改，
 * 一律返回同一种业务错误（不泄露 operation 是否属于别人），且零写入。
 * 日志只落元数据（operationId、操作类型、条数），用户内容与计划 payload 一律不进日志。
 */
@Slf4j
@Component
public class AiAssistantOperationManager {

    /** 键前缀沿用库内命名，与既有 ticket 键（huoshantuku:ai:assistant:ticket:）明显隔离 */
    static final String OPERATION_KEY_PREFIX = "huoshantuku:ai:assistant:operation:";

    /** 单次编辑张数上限：与引擎既有批量编辑工具的契约一致（MAX_IDS_PER_CALL = 50） */
    static final int MAX_PICTURE_IDS_PER_CALL = 50;

    /** 单次入库 URL 数上限：与引擎既有批量入库工具的契约一致（MAX_URLS_PER_CALL = 20） */
    static final int MAX_URLS_PER_CALL = 20;

    /** 卡片摘要预览条数（T27.10）：目标 id/URL 只列前 N 条，其余以省略行交代 */
    static final int SUMMARY_PREVIEW_LIMIT = 5;

    /** 所有查找类失败的统一文案：不泄露 operation 是否存在、属于谁 */
    static final String INVALID_OPERATION_MESSAGE = "操作不存在、已过期或已处理";

    /** 执行阶段非业务失败的统一文案：内部异常原文不出服务端日志 */
    static final String EXECUTE_FAILURE_MESSAGE = "操作执行失败，请稍后重试";

    /** URL 入库单条未知失败的固定文案：内部细节不回给前端 */
    static final String UPLOAD_FAILURE_MESSAGE = "上传失败";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 存储脚本（原子建值 + 设 TTL）：先 DEL 防同键残留，HSET 四个字段后立刻 EXPIRE，
     * 避免"HSET 与 EXPIRE 两步之间崩溃留下无 TTL 的计划"（那会把确认窗口拉长到永不失效）。
     */
    static final DefaultRedisScript<Long> STORE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('del', KEYS[1]) "
                    + "redis.call('hset', KEYS[1], 'userId', ARGV[1], 'chatId', ARGV[2], "
                    + "'digest', ARGV[3], 'plan', ARGV[4]) "
                    + "redis.call('expire', KEYS[1], ARGV[5]) "
                    + "return 1",
            Long.class);

    /**
     * 消费脚本（原子校验 + 单次删除）：userId/chatId 与调用者匹配、digest 与调用者回传一致、
     * 且计划本体经 {@code redis.sha1hex} 复核与 digest 一致，三者全过才先取计划再 DEL，
     * 整个过程在一个 Lua 脚本内——并发确认恰好一个拿到计划，被篡改的计划不会被消费执行。
     * 不用 {@code GETDEL}（本机 Redis 5.x，见 AiAssistantTicketManager 同款口径）；
     * 空串是失败哨兵（plan 字段恒为非空 JSON），任何一步不满足都保留原值。
     */
    static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 0 then return '' end "
                    + "local userId = redis.call('hget', KEYS[1], 'userId') "
                    + "local chatId = redis.call('hget', KEYS[1], 'chatId') "
                    + "local digest = redis.call('hget', KEYS[1], 'digest') "
                    + "if userId ~= ARGV[1] or chatId ~= ARGV[2] or digest ~= ARGV[3] then return '' end "
                    + "local plan = redis.call('hget', KEYS[1], 'plan') "
                    + "if not plan or plan == '' then return '' end "
                    + "if redis.sha1hex(plan) ~= digest then return '' end "
                    + "redis.call('del', KEYS[1]) "
                    + "return plan",
            String.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AiAssistantProperties properties;
    private final IPictureService pictureService;
    private final ISpaceService spaceService;

    public AiAssistantOperationManager(StringRedisTemplate stringRedisTemplate,
                                       ObjectMapper objectMapper,
                                       AiAssistantProperties properties,
                                       IPictureService pictureService,
                                       ISpaceService spaceService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.pictureService = pictureService;
        this.spaceService = spaceService;
    }

    /**
     * 准备（确认门第一步）：形状校验 + 归一化计划 → digest → 存 Redis，返回确认所需信息。
     * 只读校验仅限形状（类型/子计划/字段齐全与上限），不做任何 RBAC 判定——单一事实源在执行时。
     */
    public AiOperationPrepareVO prepare(Long userId, AiOperationPrepareRequest prepareRequest) {
        ThrowUtils.throwIf(prepareRequest == null || StrUtil.isBlank(prepareRequest.getChatId()),
                ErrorCode.PARAMS_ERROR, "会话标识不能为空");
        AiOperationPlan plan = prepareRequest.getPlan();
        ThrowUtils.throwIf(plan == null, ErrorCode.PARAMS_ERROR, "缺少操作计划");
        normalizePlan(plan);
        String planJson = serializePlan(plan);
        String digest = DigestUtil.sha1Hex(planJson);
        String operationId = newOperationId();
        stringRedisTemplate.execute(STORE_SCRIPT,
                List.of(OPERATION_KEY_PREFIX + operationId),
                String.valueOf(userId),
                prepareRequest.getChatId(),
                digest,
                planJson,
                String.valueOf(properties.getOperationTtl().getSeconds()));
        log.info("AI 助手写操作待确认, userId={}, operationId={}, type={}, entries={}",
                userId, operationId, plan.getOperationType(), planEntryCount(plan));
        AiOperationPrepareVO vo = new AiOperationPrepareVO();
        vo.setOperationId(operationId);
        vo.setOperationType(plan.getOperationType());
        vo.setDigest(digest);
        vo.setSummary(buildSummary(plan, resolveSpaceName(planSpaceId(plan))));
        return vo;
    }

    /**
     * 确认（确认门第二步）：原子校验并单次消费，然后按服务端保存的原始计划执行。
     * loginUser 取自 confirm 请求的登录态，传给既有 service 判 RBAC 与额度。
     */
    public AiOperationResultVO confirm(User loginUser, AiOperationConfirmRequest confirmRequest) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        String operationId = confirmRequest == null ? null : confirmRequest.getOperationId();
        String chatId = confirmRequest == null ? null : confirmRequest.getChatId();
        String digest = confirmRequest == null ? null : confirmRequest.getDigest();
        ThrowUtils.throwIf(StrUtil.hasBlank(operationId, chatId, digest),
                ErrorCode.NOT_FOUND_ERROR, INVALID_OPERATION_MESSAGE);
        String planJson = stringRedisTemplate.execute(CONSUME_SCRIPT,
                List.of(OPERATION_KEY_PREFIX + operationId),
                String.valueOf(loginUser.getId()), chatId, digest);
        ThrowUtils.throwIf(StrUtil.isBlank(planJson), ErrorCode.NOT_FOUND_ERROR, INVALID_OPERATION_MESSAGE);
        AiOperationPlan plan = parseStoredPlan(planJson, operationId);
        if (AiOperationPlan.TYPE_BATCH_EDIT.equals(plan.getOperationType())) {
            ThrowUtils.throwIf(plan.getBatchEdit() == null, ErrorCode.SYSTEM_ERROR, EXECUTE_FAILURE_MESSAGE);
            return executeBatchEdit(loginUser, plan.getBatchEdit());
        }
        if (AiOperationPlan.TYPE_URL_IMPORT.equals(plan.getOperationType())) {
            ThrowUtils.throwIf(plan.getUrlImport() == null, ErrorCode.SYSTEM_ERROR, EXECUTE_FAILURE_MESSAGE);
            return executeUrlImport(loginUser, plan.getUrlImport());
        }
        log.warn("AI 助手待执行计划类型无效, operationId={}", operationId);
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, EXECUTE_FAILURE_MESSAGE);
    }

    /** 形状校验 + 归一：类型与子计划一一对应、字段齐全且不超上限；trim 掉首尾空白并丢弃空标签/空 URL */
    private void normalizePlan(AiOperationPlan plan) {
        boolean batchEditType = AiOperationPlan.TYPE_BATCH_EDIT.equals(plan.getOperationType());
        boolean urlImportType = AiOperationPlan.TYPE_URL_IMPORT.equals(plan.getOperationType());
        ThrowUtils.throwIf(!batchEditType && !urlImportType, ErrorCode.PARAMS_ERROR, "不支持的操作类型");
        if (batchEditType) {
            ThrowUtils.throwIf(plan.getUrlImport() != null, ErrorCode.PARAMS_ERROR, "计划与操作类型不匹配");
            ThrowUtils.throwIf(plan.getBatchEdit() == null, ErrorCode.PARAMS_ERROR, "缺少批量编辑计划");
            normalizeBatchEdit(plan.getBatchEdit());
        } else {
            ThrowUtils.throwIf(plan.getBatchEdit() != null, ErrorCode.PARAMS_ERROR, "计划与操作类型不匹配");
            ThrowUtils.throwIf(plan.getUrlImport() == null, ErrorCode.PARAMS_ERROR, "缺少 URL 导入计划");
            normalizeUrlImport(plan.getUrlImport());
        }
    }

    private void normalizeBatchEdit(AiBatchEditPlan batchEdit) {
        ThrowUtils.throwIf(batchEdit.getSpaceId() == null, ErrorCode.PARAMS_ERROR, "缺少目标空间");
        List<Long> pictureIdList = batchEdit.getPictureIdList();
        ThrowUtils.throwIf(pictureIdList == null || pictureIdList.isEmpty(),
                ErrorCode.PARAMS_ERROR, "缺少要修改的图片");
        ThrowUtils.throwIf(pictureIdList.size() > MAX_PICTURE_IDS_PER_CALL,
                ErrorCode.PARAMS_ERROR, "单次批量编辑最多 " + MAX_PICTURE_IDS_PER_CALL + " 张");
        batchEdit.setCategory(trimToNull(batchEdit.getCategory()));
        batchEdit.setNameRule(trimToNull(batchEdit.getNameRule()));
        if (batchEdit.getTags() != null) {
            batchEdit.setTags(batchEdit.getTags().stream()
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .toList());
        }
        boolean hasEditableField = batchEdit.getCategory() != null
                || (batchEdit.getTags() != null && !batchEdit.getTags().isEmpty())
                || batchEdit.getNameRule() != null;
        ThrowUtils.throwIf(!hasEditableField, ErrorCode.PARAMS_ERROR, "至少需要一个编辑字段");
    }

    private void normalizeUrlImport(AiUrlImportPlan urlImport) {
        ThrowUtils.throwIf(urlImport.getSpaceId() == null, ErrorCode.PARAMS_ERROR, "缺少目标空间");
        List<String> fileUrls = urlImport.getFileUrls();
        ThrowUtils.throwIf(fileUrls == null || fileUrls.isEmpty(), ErrorCode.PARAMS_ERROR, "缺少 URL 列表");
        ThrowUtils.throwIf(fileUrls.size() > MAX_URLS_PER_CALL,
                ErrorCode.PARAMS_ERROR, "单次导入最多 " + MAX_URLS_PER_CALL + " 条");
        List<String> normalized = fileUrls.stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .toList();
        ThrowUtils.throwIf(normalized.isEmpty(), ErrorCode.PARAMS_ERROR, "缺少 URL 列表");
        for (String fileUrl : normalized) {
            ThrowUtils.throwIf(!fileUrl.startsWith("http://") && !fileUrl.startsWith("https://"),
                    ErrorCode.PARAMS_ERROR, "仅支持 http/https 图片直链");
        }
        urlImport.setFileUrls(normalized);
    }

    /** 批量编辑：执行内容 = 存储计划（逐字段映射既有批量编辑入参），业务失败原样上抛，未知失败收敛为固定文案 */
    private AiOperationResultVO executeBatchEdit(User loginUser, AiBatchEditPlan batchEdit) {
        PictureEditByBatchRequest request = new PictureEditByBatchRequest();
        request.setSpaceId(batchEdit.getSpaceId());
        request.setPictureIdList(batchEdit.getPictureIdList());
        request.setCategory(batchEdit.getCategory());
        request.setTags(batchEdit.getTags());
        request.setNameRule(batchEdit.getNameRule());
        try {
            pictureService.editPictureByBatch(request, loginUser);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("AI 助手批量编辑执行失败, spaceId={}, count={}",
                    batchEdit.getSpaceId(), batchEdit.getPictureIdList().size(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, EXECUTE_FAILURE_MESSAGE);
        }
        AiOperationResultVO vo = new AiOperationResultVO();
        vo.setOperationType(AiOperationPlan.TYPE_BATCH_EDIT);
        vo.setSuccess(Boolean.TRUE);
        vo.setRequestedCount(batchEdit.getPictureIdList().size());
        return vo;
    }

    /**
     * URL 入库：逐条走既有上传 service，业务失败逐条上报（文案原样，供用户理解），
     * 未知失败收敛为固定文案；登录态失效（NOT_LOGIN_ERROR）提前终止——后续条目必然同样失败，
     * 未尝试条目计入失败数并在说明里如实报数。
     */
    private AiOperationResultVO executeUrlImport(User loginUser, AiUrlImportPlan urlImport) {
        List<String> fileUrls = urlImport.getFileUrls();
        List<AiOperationItemResultVO> items = new ArrayList<>();
        int succeeded = 0;
        String fatalMessage = null;
        for (String fileUrl : fileUrls) {
            if (fatalMessage != null) {
                break;
            }
            AiOperationItemResultVO item = new AiOperationItemResultVO();
            items.add(item);
            try {
                PictureUploadRequest uploadRequest = new PictureUploadRequest();
                uploadRequest.setSpaceId(urlImport.getSpaceId());
                pictureService.uploadPicture(fileUrl, uploadRequest, loginUser);
                succeeded++;
                item.setOk(Boolean.TRUE);
            } catch (BusinessException e) {
                item.setOk(Boolean.FALSE);
                item.setMessage(e.getMessage());
                if (e.getCode() == ErrorCode.NOT_LOGIN_ERROR.getCode()) {
                    fatalMessage = "登录态已失效，剩余 " + (fileUrls.size() - items.size()) + " 条未尝试";
                }
            } catch (RuntimeException e) {
                log.warn("AI 助手 URL 入库单条执行失败, spaceId={}", urlImport.getSpaceId(), e);
                item.setOk(Boolean.FALSE);
                item.setMessage(UPLOAD_FAILURE_MESSAGE);
            }
        }
        AiOperationResultVO vo = new AiOperationResultVO();
        vo.setOperationType(AiOperationPlan.TYPE_URL_IMPORT);
        vo.setRequestedCount(fileUrls.size());
        vo.setSucceededCount(succeeded);
        vo.setFailedCount(fileUrls.size() - succeeded);
        vo.setItems(items);
        vo.setSuccess(succeeded == fileUrls.size());
        vo.setMessage(fatalMessage);
        return vo;
    }

    private AiOperationPlan parseStoredPlan(String planJson, String operationId) {
        try {
            return objectMapper.readValue(planJson, AiOperationPlan.class);
        } catch (JsonProcessingException e) {
            log.warn("AI 助手待执行计划解析失败, operationId={}", operationId);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, EXECUTE_FAILURE_MESSAGE);
        }
    }

    /**
     * 确认卡片摘要（T27.10）：从归一化后的既存计划生成，与执行内容同源，引擎与前端只透传/渲染。
     * 空间查得到给名字（id 一并保留），查不到回退展示 id——prepare 不做 RBAC，也不因空间不存在而拒绝：
     * 目标空间的归属与额度校验仍单一发生在确认执行时。
     */
    static List<String> buildSummary(AiOperationPlan plan, String spaceName) {
        List<String> lines = new ArrayList<>();
        if (AiOperationPlan.TYPE_BATCH_EDIT.equals(plan.getOperationType())) {
            AiBatchEditPlan batchEdit = plan.getBatchEdit();
            lines.add(spaceLine(batchEdit.getSpaceId(), spaceName));
            lines.add(targetPictureLine(batchEdit.getPictureIdList()));
            if (batchEdit.getCategory() != null) {
                lines.add("分类 → " + batchEdit.getCategory());
            }
            if (batchEdit.getTags() != null && !batchEdit.getTags().isEmpty()) {
                lines.add("标签 → " + String.join("、", batchEdit.getTags()));
            }
            if (batchEdit.getNameRule() != null) {
                lines.add("命名规则 → " + batchEdit.getNameRule());
            }
            return lines;
        }
        AiUrlImportPlan urlImport = plan.getUrlImport();
        List<String> fileUrls = urlImport.getFileUrls();
        lines.add(spaceLine(urlImport.getSpaceId(), spaceName));
        lines.add("图片直链：" + fileUrls.size() + " 条");
        int preview = Math.min(fileUrls.size(), SUMMARY_PREVIEW_LIMIT);
        for (int i = 0; i < preview; i++) {
            lines.add((i + 1) + ". " + fileUrls.get(i));
        }
        if (fileUrls.size() > SUMMARY_PREVIEW_LIMIT) {
            lines.add("…（其余 " + (fileUrls.size() - SUMMARY_PREVIEW_LIMIT) + " 条略）");
        }
        return lines;
    }

    private static String spaceLine(Long spaceId, String spaceName) {
        if (StrUtil.isBlank(spaceName)) {
            return "目标空间 id：" + spaceId;
        }
        return "目标空间：" + spaceName + "（id：" + spaceId + "）";
    }

    private static String targetPictureLine(List<Long> pictureIdList) {
        int total = pictureIdList.size();
        int preview = Math.min(total, SUMMARY_PREVIEW_LIMIT);
        StringBuilder line = new StringBuilder("目标图片：").append(total).append(" 张（id：");
        for (int i = 0; i < preview; i++) {
            if (i > 0) {
                line.append('、');
            }
            line.append(pictureIdList.get(i));
        }
        if (total > SUMMARY_PREVIEW_LIMIT) {
            line.append("…其余 ").append(total - SUMMARY_PREVIEW_LIMIT).append(" 张略");
        }
        return line.append('）').toString();
    }

    private Long planSpaceId(AiOperationPlan plan) {
        return AiOperationPlan.TYPE_BATCH_EDIT.equals(plan.getOperationType())
                ? plan.getBatchEdit().getSpaceId()
                : plan.getUrlImport().getSpaceId();
    }

    /** 只取空间名（卡片摘要用）；查不到返回 null，由摘要回退展示 id，不在 prepare 阶段拒绝 */
    private String resolveSpaceName(Long spaceId) {
        if (spaceId == null) {
            return null;
        }
        Space space = spaceService.getById(spaceId);
        return space == null ? null : space.getSpaceName();
    }

    private String serializePlan(AiOperationPlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            log.warn("AI 助手操作计划序列化失败, type={}", plan.getOperationType());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, EXECUTE_FAILURE_MESSAGE);
        }
    }

    /** 不可猜的操作 id：安全随机 16 字节的小写 hex（32 位） */
    private String newOperationId() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String trimToNull(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int planEntryCount(AiOperationPlan plan) {
        if (AiOperationPlan.TYPE_BATCH_EDIT.equals(plan.getOperationType())) {
            List<Long> pictureIdList = plan.getBatchEdit() == null ? null : plan.getBatchEdit().getPictureIdList();
            return pictureIdList == null ? 0 : pictureIdList.size();
        }
        List<String> fileUrls = plan.getUrlImport() == null ? null : plan.getUrlImport().getFileUrls();
        return fileUrls == null ? 0 : fileUrls.size();
    }
}
