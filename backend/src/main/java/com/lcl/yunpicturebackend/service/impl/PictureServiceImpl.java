package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.lcl.yunpicturebackend.api.aliyunai.AliYunAiApi;
import com.lcl.yunpicturebackend.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.lcl.yunpicturebackend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.lcl.yunpicturebackend.api.aliyunai.model.GetOutPaintingTaskResponse;
import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.domain.dto.file.UploadPictureResult;
import com.lcl.yunpicturebackend.domain.dto.picture.*;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureAiTagSuggestionVO;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.enums.PictureReviewStatusEnum;
import com.lcl.yunpicturebackend.enums.TagTypeEnum;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.config.CosClientConfig;
import com.lcl.yunpicturebackend.manager.CosManager;
import com.lcl.yunpicturebackend.manager.ai.PictureAiTagManager;
import com.lcl.yunpicturebackend.manager.auth.SpaceUserAuthManager;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.manager.auth.model.SpaceUserPermissionConstant;
import com.lcl.yunpicturebackend.manager.cache.PictureListCacheInvalidator;
import com.lcl.yunpicturebackend.manager.crawler.BingImageParser;
import com.lcl.yunpicturebackend.manager.observability.PictureListCacheMetrics;
import com.lcl.yunpicturebackend.manager.observability.TraceContext;
import com.lcl.yunpicturebackend.manager.upload.FilePictureUpload;
import com.lcl.yunpicturebackend.manager.upload.PictureUploadTemplate;
import com.lcl.yunpicturebackend.manager.upload.URLFilePictureUpload;
import com.lcl.yunpicturebackend.mapper.PictureMapper;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.PictureFileCleanupService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.ITagService;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.utils.ColorSimilarUtils;
import com.lcl.yunpicturebackend.utils.SqlSortUtils;
import com.lcl.yunpicturebackend.utils.TextSanitizeUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.lcl.yunpicturebackend.constant.UserConstant.USER_LOGIN_STATE;

/**
 * <p>
 * 图片 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-04-18
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PictureServiceImpl extends ServiceImpl<PictureMapper, Picture> implements IPictureService {

    /**
     * AI 扩图任务归属记录的 Redis key 前缀
     */
    static final String OUT_PAINTING_OWNER_KEY = "huoshantuku:outpainting:owner:";

    /**
     * AI 扩图任务幂等键前缀：用户 + 图片 + 参数指纹，保证同参数重复提交只产生一个付费任务
     */
    static final String OUT_PAINTING_IDEMPOTENT_KEY = "huoshantuku:outpainting:idempotent:";

    /**
     * AI 扩图每日配额键前缀（按用户 + 自然日）
     */
    static final String OUT_PAINTING_QUOTA_KEY = "huoshantuku:outpainting:quota:";

    /**
     * 幂等占位值：任务已提交但尚未拿到 taskId
     */
    private static final String OUT_PAINTING_PENDING = "PENDING";

    /**
     * 图片列表缓存版本号的 Redis key
     */
    private static final String PICTURE_LIST_CACHE_VERSION_KEY = "huoshantuku:listPictureVOByPage:version";

    /**
     * 释放分布式锁的 Lua 脚本：仅当锁的 value 与持有者标识一致时才删除，避免误删已超时后他人持有的锁
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 回滚扩图配额占用的 Lua 脚本：key 不存在或已为 0 时什么都不做。
     * 避免跨天 key 刚过期时 decrement 凭空造出一个 -1 且没有 TTL 的残留 key。
     */
    private static final DefaultRedisScript<Long> QUOTA_ROLLBACK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 and tonumber(redis.call('get', KEYS[1])) > 0 "
                    + "then return redis.call('decr', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 缓存重建抢锁失败后的重试次数与间隔（总等待约 250ms）
     */
    private static final int CACHE_REBUILD_RETRY_TIMES = 5;
    private static final long CACHE_REBUILD_RETRY_INTERVAL_MS = 50;

    /**
     * 缓存重建锁的 TTL（秒）。
     * <p>
     * 取值必须大于"查库 + 组装 VO + 序列化"的耗时上界：锁一旦在持有者仍在重建时提前过期，
     * 后续请求会再抢到锁并重复查库（双写竞争），双重检查只能减轻影响、不能消除重复回源。
     * 实测本机单次重建在百毫秒级，30 秒留了一个数量级余量；可通过配置按 P95 重建耗时调整。
     * <p>
     * 不做看门狗自动续期、也不引 Redisson：自研锁是为了理解原理，单机 + 单 Redis 够用，
     * 不值得为一个锁引入整套客户端；真需要续期再换 Redisson 或加后台续期线程。
     */
    @Value("${app.cache.rebuild-lock-ttl-seconds:30}")
    private long cacheRebuildLockTtlSeconds;

    /**
     * 重建耗时超过该阈值打 WARN，作为"重建变慢、TTL 余量可能不够"的早期信号
     */
    @Value("${app.cache.rebuild-warn-ms:5000}")
    private long cacheRebuildWarnMs;

    /**
     * 单个用户每日可创建的 AI 扩图任务上限（扩图按量计费，必须有硬上限）
     */
    @Value("${app.outpainting.daily-quota:20}")
    private int outPaintingDailyQuota;

    private final IUserService userService;

    private final ISpaceService spaceService;

    private final ITagService tagService;

    private final SpaceUserAuthManager spaceUserAuthManager;

    private final FilePictureUpload pictureUpload;

    private final URLFilePictureUpload urlFilePictureUpload;

    private final StringRedisTemplate stringRedisTemplate;

    private final TransactionTemplate transactionTemplate;

    private final CosManager cosManager;

    private final CosClientConfig cosClientConfig;

    private final AliYunAiApi aliYunAiApi;

    private final PictureAiTagManager pictureAiTagManager;

    private final PictureFileCleanupService pictureFileCleanupService;

    private final PictureListCacheInvalidator pictureListCacheInvalidator;

    private final BingImageParser bingImageParser;

    /**
     * 图片列表本地缓存，Bean 定义见 CacheConfig（已开启 recordStats，用于统计命中率）
     */
    @Resource(name = "pictureListLocalCache")
    private Cache<String, String> localCache;

    /**
     * 缓存命中率埋点
     */
    @Resource
    private PictureListCacheMetrics cacheMetrics;
    @Resource
    private ExecutorService pictureUploadExecutor;

    @Override
    public PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser) {
        // 判断用户是否拥有权限
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // 空间权限校验
        Long spaceId = pictureUploadRequest.getSpaceId();
        Space space = null;
        if (spaceId != null) {
            space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            checkSpaceUploadPermission(space, loginUser);
        }
        // 如果是更新图片，则需要判断图片是否存在
        Picture oldPicture = null;
        Long pictureId = null;
        if (pictureUploadRequest != null) {
            pictureId = pictureUploadRequest.getId();
        }
        if (pictureId != null) {
            oldPicture = this.getById(pictureId);
            ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            // 仅本人或管理员可编辑
            ThrowUtils.throwIf(!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser), ErrorCode.NO_AUTH_ERROR);
            // 校验空间是否一致
            // 如果没有传送空间id，则使用图片原来的空间id
            if (spaceId == null) {
                if (oldPicture.getSpaceId() != null) {
                    spaceId = oldPicture.getSpaceId();
                }
            } else {
                // 传了空间id，则必须和原图一致
                if (ObjUtil.notEqual(spaceId, oldPicture.getSpaceId())) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间 id 不一致");
                }
            }
        }
        // 空间上传权限收口（T3.10）：spaceId 由原图反推的替换路径与显式传 spaceId 同口径，
        // 防止被移出空间/降权的原上传者绕过空间权限替换图片文件
        if (spaceId != null && space == null) {
            space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            checkSpaceUploadPermission(space, loginUser);
        }
        // 校验额度，判断空间是否达到上限：仅新增图片需要预检；替换不增条数、大小按净差值在事务内原子校验，
        // 按新增口径预检会把"空间已满但替换图片"的场景误拒
        if (oldPicture == null && space != null) {
            if (space.getTotalCount() >= space.getMaxCount()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间条数不足");
            }
            if (space.getTotalSize() >= space.getMaxSize()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间大小不足");
            }
        }

        // 如果是URL图片（抓取到的URL），则需要判断图片是否存在
        if (inputSource instanceof String) {
            String imageUrl = (String) inputSource;
            Picture existingPicture = this.getByUrl(imageUrl);
            if (existingPicture != null) {
                log.warn("图片已存在，URL: {}, 已有ID: {}", imageUrl, existingPicture.getId());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片已存在");
            }
        }
        // 上传图片，获取信息
        /*// 按用户id划分目录
        String uploadPathPrefix = String.format("public/%s", loginUser.getId());*/
        // 按照用户 id 划分目录 => 按照空间划分目录
        String uploadPathPrefix;
        if (spaceId == null) {
            uploadPathPrefix = String.format("public/%s", loginUser.getId());
        } else {
            uploadPathPrefix = String.format("space/%s", spaceId);
        }

        PictureUploadTemplate pictureUploadTemplate;
        if (inputSource instanceof MultipartFile) {
            pictureUploadTemplate = pictureUpload;
        } else {
            pictureUploadTemplate = urlFilePictureUpload;
        }
        UploadPictureResult uploadPictureResult = pictureUploadTemplate.uploadPicture(inputSource, uploadPathPrefix);
        // 构造要上传的图片信息
        Picture picture = getPicture(loginUser, uploadPictureResult, pictureUploadRequest, oldPicture);
        // 填充 spaceId
        picture.setSpaceId(spaceId);
        // 填充图片颜色
        picture.setPicColor(uploadPictureResult.getPicColor());
        // 填充审核信息
        fillReviewParams(picture, loginUser);
        // 开启事务
        Long finalSpaceId = spaceId;
        Picture finalOldPicture = oldPicture;
        try {
            transactionTemplate.execute(status -> {
                boolean result = this.saveOrUpdate(picture);
                ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片上传失败");
                if (finalSpaceId != null) {
                    if (finalOldPicture != null) {
                        // 替换图片：额度只记净差值、条数不变；GREATEST 防止历史脏数据把 totalSize 减成负数
                        boolean update = spaceService.lambdaUpdate()
                                .eq(Space::getId, finalSpaceId)
                                .apply("GREATEST(totalSize - {0}, 0) + {1} <= maxSize",
                                        finalOldPicture.getPicSize(), picture.getPicSize())
                                .setSql("totalSize = GREATEST(totalSize - " + finalOldPicture.getPicSize()
                                        + ", 0) + " + picture.getPicSize())
                                .update();
                        ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "空间额度不足");
                    } else {
                        // 条件原子更新：并发下也保证额度不超限，条件不满足时影响行数为 0，事务回滚
                        boolean update = spaceService.lambdaUpdate()
                                .eq(Space::getId, finalSpaceId)
                                .apply("totalSize + {0} <= maxSize", picture.getPicSize())
                                .apply("totalCount + 1 <= maxCount")
                                .setSql("totalSize = totalSize + " + picture.getPicSize())
                                .setSql("totalCount = totalCount + 1")
                                .update();
                        ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "空间额度不足");
                    }
                }
                return picture;
            });
        } catch (RuntimeException e) {
            // 事务已回滚：COS 上传在事务之前完成，刚上传的新文件不再被任何记录引用，
            // 补偿删除避免留孤儿对象；cleanupPictureFile 按引用计数兜底，不会误删仍被占用的文件
            this.cleanupPictureFile(picture);
            throw e;
        }

        // 替换后旧 COS 对象随记录改指向而失去引用，事务提交成功才清理；URL 未变说明文件没换，不能误删
        if (finalOldPicture != null && ObjUtil.notEqual(finalOldPicture.getUrl(), picture.getUrl())) {
            this.cleanupPictureFile(finalOldPicture);
        }
        // 清除缓存
        this.clearPictureListCache();
        return PictureVO.objToVo(picture);
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    class UploadResult {
        private int index;
        private String url;
        private boolean success;
        private Long pictureId;
        private String errorMessage;
    }

    @Override
    public int uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser) {
        String searchText = pictureUploadByBatchRequest.getSearchText();
        String namePrefix = pictureUploadByBatchRequest.getNamePrefix();
        Integer offset = pictureUploadByBatchRequest.getOffset();
        if (StrUtil.isBlank(namePrefix)) {
            namePrefix = searchText;
        }

        ThrowUtils.throwIf(pictureUploadByBatchRequest.getCount() > 30, ErrorCode.PARAMS_ERROR, "图片数量不能超过30张");

//        String fetchURL = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1", searchText);
        String fetchURL = String.format("https://cn.bing.com/images/async?q=%s&first=%d&mmasync=1",
                searchText, offset + 10);
        Document document;
        try {
            document = Jsoup.connect(fetchURL)
                    // 出站请求带上 traceId：外部依赖超时/改版时，可以和本地日志串起来
                    .header(TraceContext.TRACE_ID_HEADER, TraceContext.currentOrDefault())
                    .get();
        } catch (IOException e) {
            log.error("获取页面失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取页面失败");
        }

        // 解析原图地址：选择器与字段名走配置（app.bing-image.*），
        // 契约由 BingImageParserContractTest 用固定样本钉住，Bing 改版时构建期就能暴露
        List<String> imageUrls = bingImageParser.parseImageUrls(document.html(),
                pictureUploadByBatchRequest.getCount());
        List<Map.Entry<Integer, String>> indexedUrls = new ArrayList<>(imageUrls.size());
        for (int i = 0; i < imageUrls.size(); i++) {
            indexedUrls.add(new AbstractMap.SimpleEntry<>(i, imageUrls.get(i)));
        }
        String finalNamePrefix = namePrefix;
        List<CompletableFuture<UploadResult>> futures = indexedUrls.stream()
                .map(entry -> {
                    int currentIndex = entry.getKey() + 1;
                    String fileURL = entry.getValue();

                    return CompletableFuture.supplyAsync(() -> {
                        UploadResult result = new UploadResult();
                        result.setIndex(currentIndex);
                        result.setUrl(fileURL);

                        try {
                            PictureUploadRequest pictureUploadRequest = new PictureUploadRequest();
                            if (StrUtil.isNotBlank(finalNamePrefix)) {
                                pictureUploadRequest.setPicName(finalNamePrefix + currentIndex);
                            }

                            PictureVO pictureVO = this.uploadPicture(fileURL, pictureUploadRequest, loginUser);
                            result.setSuccess(true);
                            result.setPictureId(pictureVO.getId());
                            log.info("上传图片成功 [{}]: {}", currentIndex, pictureVO.getId());
                        } catch (Exception e) {
                            result.setSuccess(false);
                            result.setErrorMessage(e.getMessage());
                            log.error("上传图片失败 [{}] URL: {}, 错误: {}", currentIndex, fileURL, e.getMessage());
                        }

                        return result;
                    }, pictureUploadExecutor);
                })
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<UploadResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        long successCount = results.stream().filter(UploadResult::isSuccess).count();
        long failCount = results.size() - successCount;

        log.info("批量上传完成，总数: {}, 成功: {}, 失败: {}", results.size(), successCount, failCount);

        results.forEach(result -> {
            if (!result.isSuccess()) {
                log.warn("图片上传失败 [{}], URL: {}, 原因: {}",
                        result.getIndex(), result.getUrl(), result.getErrorMessage());
            }
        });

        return (int) successCount;
    }

    /**
     * 获取图片信息
     *
     * @param loginUser 登录用户
     * @param uploadPictureResult 上传图片结果
     * @param pictureUploadRequest 图片上传请求
     * @param oldPicture 更新前的图片记录，新增时为 null
     * @return 图片信息
     */
    private static Picture getPicture(User loginUser, UploadPictureResult uploadPictureResult, PictureUploadRequest pictureUploadRequest, Picture oldPicture) {
        Picture picture = new Picture();
        picture.setUrl(uploadPictureResult.getUrl());
        picture.setThumbnailUrl(uploadPictureResult.getThumbnailUrl());
        String picName = uploadPictureResult.getPicName();
        if (pictureUploadRequest != null && StrUtil.isNotBlank(pictureUploadRequest.getPicName())) {
            picName = pictureUploadRequest.getPicName();
        }
        // 图片名可能来自上传文件名或用户输入，统一清洗
        picture.setName(TextSanitizeUtils.stripHtml(picName));
        picture.setPicSize(uploadPictureResult.getPicSize());
        picture.setPicWidth(uploadPictureResult.getPicWidth());
        picture.setPicHeight(uploadPictureResult.getPicHeight());
        picture.setPicScale(uploadPictureResult.getPicScale());
        picture.setPicFormat(uploadPictureResult.getPicFormat());
        // 新增图片归属上传人；更新图片保留原归属（管理员替换他人图片不能改变归属）
        picture.setUserId(oldPicture != null ? oldPicture.getUserId() : loginUser.getId());
        // 如果oldPicture不为null，则是更新图片，否则是新增图片
        if (oldPicture != null) {
            // 更新图片还要设置修改时间，补充id
            picture.setId(oldPicture.getId());
            picture.setEditTime(new Date());
        }
        return picture;
    }

    /**
     * 校验用户对目标空间的上传权限（私有空间仅属主/站点管理员，团队空间按数据库成员角色判断），
     * 与 checkPictureAuth 的空间判权同源
     *
     * @param space 目标空间
     * @param loginUser 登录用户
     */
    private void checkSpaceUploadPermission(Space space, User loginUser) {
        List<String> spacePermissions = spaceUserAuthManager.getPermissionList(space, loginUser);
        ThrowUtils.throwIf(!spacePermissions.contains(SpaceUserPermissionConstant.PICTURE_UPLOAD),
                ErrorCode.NO_AUTH_ERROR, "用户没有空间权限");
    }

    /**
     * 分页获取图片封装
     */
    @Override
    public Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request) {
        List<Picture> pictureList = picturePage.getRecords();
        Page<PictureVO> pictureVOPage = new Page<>(picturePage.getCurrent(), picturePage.getSize(), picturePage.getTotal());
        if (CollUtil.isEmpty(pictureList)) {
            return pictureVOPage;
        }
        // 对象列表 => 封装对象列表
        List<PictureVO> pictureVOList = pictureList.stream().map(PictureVO::objToVo).collect(Collectors.toList());
        // 1. 关联查询用户信息
        Set<Long> userIdSet = pictureList.stream().map(Picture::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        pictureVOList.forEach(pictureVO -> {
            Long userId = pictureVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            pictureVO.setUser(userService.getUserVO(user));
        });
        pictureVOPage.setRecords(pictureVOList);
        return pictureVOPage;
    }

    @Override
    public PictureVO getPictureVO(Picture picture, HttpServletRequest request) {
        // 对象转封装类
        PictureVO pictureVO = PictureVO.objToVo(picture);
        // 关联查询用户信息
        Long userId = picture.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            pictureVO.setUser(userVO);
        }
        return pictureVO;
    }

    @Override
    public void validPicture(Picture picture) {
        ThrowUtils.throwIf(picture == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        Long id = picture.getId();
        String url = picture.getUrl();
        String introduction = picture.getIntroduction();
        // 修改数据时，id 不能为空，有参数则校验
        ThrowUtils.throwIf(ObjUtil.isNull(id), ErrorCode.PARAMS_ERROR, "id 不能为空");
        if (StrUtil.isNotBlank(url)) {
            ThrowUtils.throwIf(url.length() > 1024, ErrorCode.PARAMS_ERROR, "url 过长");
        }
        if (StrUtil.isNotBlank(introduction)) {
            ThrowUtils.throwIf(introduction.length() > 800, ErrorCode.PARAMS_ERROR, "简介过长");
        }
    }

    @Override
    @Transactional
    public void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser) {
        Long id = pictureReviewRequest.getId();
        Integer reviewStatus = pictureReviewRequest.getReviewStatus();
        PictureReviewStatusEnum pictureReviewStatusEnum = PictureReviewStatusEnum.getEnumByValue(reviewStatus);
        if (id == null || reviewStatus == null || PictureReviewStatusEnum.REVIEWING.equals(pictureReviewStatusEnum)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 判断参数是否存在
        Picture oldPicture = this.getById(id);
        if (oldPicture == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 已是该状态，则不能修改
        if (oldPicture.getReviewStatus().equals(reviewStatus)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请勿重复审核");
        }
        // 更新审核状态
        Picture updatePicture = new Picture();
        BeanUtil.copyProperties(pictureReviewRequest, updatePicture);
        updatePicture.setReviewerId(loginUser.getId());
        updatePicture.setReviewTime(new Date());
        boolean success = this.updateById(updatePicture);
        ThrowUtils.throwIf(!success, ErrorCode.OPERATION_ERROR);
        // 清除缓存
        this.clearPictureListCache();
    }

    @Override
    public void fillReviewParams(Picture picture, User loginUser) {
        if (userService.isAdmin(loginUser)) {
            // 如果是管理员，自动过审
            picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            picture.setReviewTime(new Date());
            picture.setReviewerId(loginUser.getId());
            picture.setReviewMessage("管理员自动过审");
        } else {
            // 否则，创建或编辑图片都要设置为待审核
            picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        }
    }

    @Override
    public Page<PictureVO> listPictureVOByPageByCache(PictureQueryRequest pictureQueryRequest, HttpServletRequest request) {
        long current = pictureQueryRequest.getCurrent();// 当前页
        long size = pictureQueryRequest.getPageSize();// 每页大小
        // 限制爬虫
        ThrowUtils.throwIf(size > 100, ErrorCode.PARAMS_ERROR);
        // 空间权限校验：与 listPictureVOByPage 保持一致，防止缓存接口越权枚举空间图片
        Long spaceId = pictureQueryRequest.getSpaceId();
        if (spaceId == null) {
            // 普通用户默认只能查看已经过审的公开图片（spaceId 为空，避免把空间图片混入公共列表）
            pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            pictureQueryRequest.setNullSpaceId(true);
        } else {
            // 空间图库：按目标空间显式判定查看权限（目标绑定，不依赖请求嗅探）
            checkSpaceViewPermission(spaceId, request);
        }
        // 构建缓存key
        // 将查询条件序列化后取 MD5，作为缓存 key 的筛选条件部分
        String hashKey = DigestUtils.md5DigestAsHex(
                JSONUtil.toJsonStr(buildCacheKey(pictureQueryRequest)).getBytes()
        );
        // 先从本地缓存 Caffeine 中获取（本地缓存不携带版本号，依靠 clearPictureListCache 中的 invalidateAll 失效）
        String cachedValue = localCache.getIfPresent(hashKey);
        if (StringUtils.isNotBlank(cachedValue)) {
            // 如果命中缓存，返回结果
            Page<PictureVO> pictureVOPage = JSONUtil.toBean(cachedValue, new TypeReference<Page<PictureVO>>() {}, false);
            return pictureVOPage;
        }
        // Redis key 携带版本号：清理缓存时只需将版本号 +1，旧 key 靠 TTL 自然过期
        long cacheVersion = getListCacheVersion();
        String key = String.format("huoshantuku:listPictureVOByPage:%d:%s", cacheVersion, hashKey);
        // 本地缓存中没有，再从分布式缓存（Redis）中获取
        cachedValue = getFromRedisCache(key);
        if (StringUtils.isNotBlank(cachedValue)) {
            // 回写本地缓存
            localCache.put(hashKey, cachedValue);
            // 如果命中缓存，返回结果
            Page<PictureVO> pictureVOPage = JSONUtil.toBean(cachedValue, new TypeReference<Page<PictureVO>>() {}, false);
            return pictureVOPage;
        }
        // 缓存都没有，使用分布式锁防止缓存击穿
        String lockKey = "huoshantuku:lock:" + hashKey;
        // value 使用随机标识，释放时校验归属，避免误删他人锁
        String lockValue = UUID.randomUUID().toString();
        Boolean lock = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, cacheRebuildLockTtlSeconds, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(lock)) {
            // 获取锁成功
            long rebuildStart = System.currentTimeMillis();
            try {
                // 双重检查：获取锁后再检查一次缓存
                cachedValue = getFromRedisCache(key);
                if (StringUtils.isNotBlank(cachedValue)) {
                    // 回写本地缓存
                    localCache.put(hashKey, cachedValue);
                    // 如果命中缓存，返回结果
                    Page<PictureVO> pictureVOPage = JSONUtil.toBean(cachedValue, new TypeReference<Page<PictureVO>>() {}, false);
                    return pictureVOPage;
                }
                // 查询数据库 + 组装封装类
                Page<PictureVO> pictureVOPage = loadFromDb(pictureQueryRequest, current, size, request);
                // 写入本地缓存
                String cacheValue = JSONUtil.toJsonStr(pictureVOPage);
                localCache.put(hashKey, cacheValue);
                // 写入分布式缓存
                // 设置过期时间 5 ~ 10 分钟随机过期，防止缓存雪崩
                int cacheExpireTime;
                if (pictureVOPage.getRecords() == null || pictureVOPage.getRecords().isEmpty()) {
                    cacheExpireTime = 60; // 空结果缓存1分钟
                    log.info("空结果缓存，key: {}, 过期时间: {}s", key, cacheExpireTime);
                } else {
                    cacheExpireTime = 300 + RandomUtil.randomInt(0, 300); // 正常结果5-10分钟
                }
                stringRedisTemplate.opsForValue().set(key, cacheValue, cacheExpireTime, TimeUnit.SECONDS);
                return pictureVOPage;
            } finally {
                long rebuildCost = System.currentTimeMillis() - rebuildStart;
                if (rebuildCost > cacheRebuildWarnMs) {
                    // 重建耗时逼近锁 TTL 时应提前告警扩容/优化回源，而不是等锁提前释放暴露成重复查库
                    log.warn("[cache-rebuild] 重建耗时 {}ms 超过预警阈值 {}ms（锁 TTL={}s）, key={}",
                            rebuildCost, cacheRebuildWarnMs, cacheRebuildLockTtlSeconds, key);
                } else {
                    log.info("[cache-rebuild] 重建完成, 耗时={}ms, 锁 TTL={}s, key={}",
                            rebuildCost, cacheRebuildLockTtlSeconds, key);
                }
                // 仅当 value 匹配时才释放，避免锁超时后误删他人持有的锁
                stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
            }
        } else {
            // 获取锁失败：短暂等待后有限重试，等持有者重建缓存
            for (int i = 0; i < CACHE_REBUILD_RETRY_TIMES; i++) {
                try {
                    Thread.sleep(CACHE_REBUILD_RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                cachedValue = getFromRedisCache(key);
                if (StringUtils.isNotBlank(cachedValue)) {
                    localCache.put(hashKey, cachedValue);
                    return JSONUtil.toBean(cachedValue, new TypeReference<Page<PictureVO>>() {}, false);
                }
            }
            // 返回空 Page 会误导前端“没有数据”，改为明确提示重试
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "系统繁忙，请稍后重试");
        }
    }

    /**
     * 缓存未命中时的回源：查库 + 组装 VO。
     * <p>
     * 单独抽出来是为了给"重建耗时"一个明确的边界：锁 TTL 必须覆盖它，
     * 测试也能在这里注入慢查询来验证锁不会提前释放。
     */
    protected Page<PictureVO> loadFromDb(PictureQueryRequest pictureQueryRequest, long current, long size,
                                         HttpServletRequest request) {
        Page<Picture> picturePage = page(new Page<>(current, size), getQueryWrapper(pictureQueryRequest));
        return getPictureVOPage(picturePage, request);
    }

    /**
     * 读取 Redis 缓存并记录命中率。
     * 首次读取、抢锁后的双重检查、抢锁失败后的重试读取都走这里，避免漏埋点导致命中率失真。
     */
    private String getFromRedisCache(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isNotBlank(value)) {
            cacheMetrics.recordRedisHit();
        } else {
            cacheMetrics.recordRedisMiss();
        }
        return value;
    }

    /**
     * 构建缓存Key
     * @param request
     * @return
     */
    private PictureQueryRequest buildCacheKey(PictureQueryRequest request) {
        // 保留所有影响查询结果的字段；分页参数决定返回哪一页数据，必须参与 key
        PictureQueryRequest cacheKey = new PictureQueryRequest();
        // spaceId 决定查询的空间范围（公共列表仅 spaceId 为空），必须参与缓存键，防止跨空间串缓存
        cacheKey.setSpaceId(request.getSpaceId());
        cacheKey.setNullSpaceId(request.isNullSpaceId());
        cacheKey.setReviewStatus(request.getReviewStatus());
        cacheKey.setCategory(request.getCategory());
        cacheKey.setTags(request.getTags());
        cacheKey.setUserId(request.getUserId());
        cacheKey.setName(request.getName());
        cacheKey.setIntroduction(request.getIntroduction());
        cacheKey.setSearchText(request.getSearchText());
        cacheKey.setPicFormat(request.getPicFormat());
        cacheKey.setSortField(request.getSortField());
        cacheKey.setSortOrder(request.getSortOrder());
        cacheKey.setCurrent(request.getCurrent());
        cacheKey.setPageSize(request.getPageSize());
        return cacheKey;
    }

    /**
     * 校验空间图库查看权限：区分"空间登录态失效"与"确实无权限"，便于前端提示重新登录。
     * 普通登录态（Spring Session）与空间登录态（Sa-Token space）必须指向同一账号。
     * 权限判定按 spaceId 对应空间的落库归属/成员角色显式构造（目标绑定）；
     * 不能用 StpKit.SPACE.hasPermission——其上下文从请求参数嗅探，攻击者伪造 spaceUserId、
     * 或用带 charset 的 Content-Type 令上下文为空，都能拿到默认授予的 picture:view。
     */
    private void checkSpaceViewPermission(Long spaceId, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object sessionUser = session == null ? null : session.getAttribute(USER_LOGIN_STATE);
        Long loginUserId = sessionUser instanceof User ? ((User) sessionUser).getId() : null;
        if (loginUserId == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        Object spaceLoginId = StpKit.SPACE.getLoginIdDefaultNull();
        if (spaceLoginId == null || !loginUserId.toString().equals(spaceLoginId.toString())) {
            throw new BusinessException(ErrorCode.SPACE_NOT_LOGIN);
        }
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        User loginUser = userService.getById(loginUserId);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        List<String> spacePermissions = spaceUserAuthManager.getPermissionList(space, loginUser);
        ThrowUtils.throwIf(!spacePermissions.contains(SpaceUserPermissionConstant.PICTURE_VIEW),
                ErrorCode.NO_AUTH_ERROR);
    }

    /**
     * 清空图片列表缓存
     */
    private void clearPictureListCache() {
        // 事务提交后再失效缓存，避免"清缓存 → 并发请求回源读到未提交旧数据 → 旧数据写回缓存"的竞态
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doClearPictureListCache();
                }
            });
        } else {
            doClearPictureListCache();
        }
    }

    /**
     * 实际清理图片列表缓存
     */
    private void doClearPictureListCache() {
        // 版本号 +1 使所有旧版本 key 立即失效，旧 key 靠 TTL 自然过期
        // 避免使用 KEYS 通配符全库扫描阻塞 Redis
        stringRedisTemplate.opsForValue().increment(PICTURE_LIST_CACHE_VERSION_KEY);
        // 先自增版本号再广播：其它实例收到广播清空本地缓存后回源，读到的是新版本 key，
        // 不会把旧数据写回本地。广播失败时由本地 TTL（5~10 分钟）兜底
        pictureListCacheInvalidator.invalidateAll();
    }

    /**
     * 获取图片列表缓存的当前版本号
     */
    private long getListCacheVersion() {
        String version = stringRedisTemplate.opsForValue().get(PICTURE_LIST_CACHE_VERSION_KEY);
        return version != null ? Long.parseLong(version) : 0L;
    }

    @Override
    @Transactional
    public void deletePicture(DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long pictureId = deleteRequest.getId();
        // 判断是否存在
        Picture oldPicture = getById(pictureId);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 校验权限
        this.checkPictureAuth(loginUser, oldPicture, SpaceUserPermissionConstant.PICTURE_DELETE);
        // 开启事务
        transactionTemplate.execute(status -> {
            // 操作数据库
            boolean result = this.removeById(pictureId);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
            // 释放额度（原子更新，防止减到负数）
            Long spaceId = oldPicture.getSpaceId();
            if (spaceId != null) {
                boolean update = spaceService.lambdaUpdate()
                        .eq(Space::getId, spaceId)
                        .setSql("totalSize = GREATEST(totalSize - " + oldPicture.getPicSize() + ", 0)")
                        .setSql("totalCount = GREATEST(totalCount - 1, 0)")
                        .update();
                ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "额度更新失败");
            }
            return true;
        });
        // 异步清除 cos 中的图片资源
        this.cleanupPictureFile(oldPicture);
        // 清除缓存
        clearPictureListCache();
    }

    @Override
    public void updatePicture(PictureUpdateRequest pictureUpdateRequest, HttpServletRequest request) {
        if (pictureUpdateRequest == null || pictureUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // UGC 文本清洗：剥离 HTML 标签防存储型 XSS
        pictureUpdateRequest.setName(TextSanitizeUtils.stripHtml(pictureUpdateRequest.getName()));
        pictureUpdateRequest.setIntroduction(TextSanitizeUtils.stripHtml(pictureUpdateRequest.getIntroduction()));
        pictureUpdateRequest.setCategory(TextSanitizeUtils.stripHtml(pictureUpdateRequest.getCategory()));
        pictureUpdateRequest.setTags(TextSanitizeUtils.stripHtmlList(pictureUpdateRequest.getTags()));
        // 将实体类和 DTO 进行转换
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureUpdateRequest, picture);
        // 注意将 list 转为 string
        picture.setTags(JSONUtil.toJsonStr(pictureUpdateRequest.getTags()));
        // 数据校验
        this.validPicture(picture);
        // 判断是否存在
        long id = pictureUpdateRequest.getId();
        Picture oldPicture = getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 补充审核参数
        User loginUser = userService.getLoginUser(request);
        this.fillReviewParams(picture, loginUser);
        // 操作数据库
        boolean result = this.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 编辑元数据不涉及文件变更，不能清理 COS 中的图片资源
        // 清除缓存
        this.clearPictureListCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editPicture(PictureEditRequest pictureEditRequest, HttpServletRequest request) {
        if (pictureEditRequest == null || pictureEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // UGC 文本清洗：剥离 HTML 标签防存储型 XSS
        pictureEditRequest.setName(TextSanitizeUtils.stripHtml(pictureEditRequest.getName()));
        pictureEditRequest.setIntroduction(TextSanitizeUtils.stripHtml(pictureEditRequest.getIntroduction()));
        pictureEditRequest.setCategory(TextSanitizeUtils.stripHtml(pictureEditRequest.getCategory()));
        pictureEditRequest.setTags(TextSanitizeUtils.stripHtmlList(pictureEditRequest.getTags()));
        // 在此处将实体类和 DTO 进行转换
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureEditRequest, picture);
        // 注意将 list 转为 string
        picture.setTags(JSONUtil.toJsonStr(pictureEditRequest.getTags()));
        // 设置编辑时间
        picture.setEditTime(new Date());
        // 数据校验
        this.validPicture(picture);
        User loginUser = userService.getLoginUser(request);
        // 判断是否存在
        long id = pictureEditRequest.getId();
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 校验权限
        this.checkPictureAuth(loginUser, oldPicture, SpaceUserPermissionConstant.PICTURE_EDIT);
        // 补充审核参数
        this.fillReviewParams(picture, loginUser);
        // 操作数据库
        boolean result = this.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 词表同事务 upsert：编辑写入的 tags/category 各计一次（先复用已有词 +1，新词注册）
        if (CollUtil.isNotEmpty(pictureEditRequest.getTags())) {
            tagService.upsertVocabulary(pictureEditRequest.getTags(), TagTypeEnum.TAG);
        }
        if (StrUtil.isNotBlank(pictureEditRequest.getCategory())) {
            tagService.upsertVocabulary(
                    Collections.singletonList(pictureEditRequest.getCategory()), TagTypeEnum.CATEGORY);
        }
        // 清除缓存
        this.clearPictureListCache();
    }

    /**
     * 按引用计数异步清理 COS 中的图片文件，仅当该 URL 已无任何存活记录引用时才真正删除。
     * <p>
     * 三类调用前提：记录已删除、记录已改指向新 URL、事务回滚后清理刚上传的孤儿文件（T3.11）——
     * 三种情形下 count 都只含其它存活引用，count > 0 即说明文件仍被占用，必须跳过清理。
     *
     * @param picture 待清理的图片记录
     */
    private void cleanupPictureFile(Picture picture) {
        String pictureUrl = picture.getUrl();
        long count = this.lambdaQuery()
                .eq(Picture::getUrl, pictureUrl)
                .count();
        // 该 URL 仍被其它记录引用，不清理
        if (count > 0) {
            return;
        }
        pictureFileCleanupService.clearPictureFile(picture);
    }

    @Override
    public QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest) {
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        if (pictureQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = pictureQueryRequest.getId();
        String name = pictureQueryRequest.getName();
        String introduction = pictureQueryRequest.getIntroduction();
        String category = pictureQueryRequest.getCategory();
        List<String> tags = pictureQueryRequest.getTags();
        Long picSize = pictureQueryRequest.getPicSize();
        Integer picWidth = pictureQueryRequest.getPicWidth();
        Integer picHeight = pictureQueryRequest.getPicHeight();
        Double picScale = pictureQueryRequest.getPicScale();
        String picFormat = pictureQueryRequest.getPicFormat();
        String searchText = pictureQueryRequest.getSearchText();
        Long userId = pictureQueryRequest.getUserId();
        Long spaceId = pictureQueryRequest.getSpaceId();
        boolean nullSpaceId = pictureQueryRequest.isNullSpaceId();
        String sortField = pictureQueryRequest.getSortField();
        String sortOrder = pictureQueryRequest.getSortOrder();
        Long reviewerId = pictureQueryRequest.getReviewerId();
        Integer reviewStatus = pictureQueryRequest.getReviewStatus();
        String reviewMessage = pictureQueryRequest.getReviewMessage();
        Date startEditTime = pictureQueryRequest.getStartEditTime();
        Date endEditTime = pictureQueryRequest.getEndEditTime();
        // 从多字段中搜索
        if (StrUtil.isNotBlank(searchText)) {
            // 需要拼接查询条件
            queryWrapper.and(qw -> qw.like("name", searchText)
                    .or()
                    .like("introduction", searchText)
            );
        }
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceId), "spaceId", spaceId);
        queryWrapper.isNull(nullSpaceId, "spaceId");
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.like(StrUtil.isNotBlank(reviewMessage), "reviewMessage", reviewMessage);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewerId), "reviewerId", reviewerId);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        queryWrapper.ge(ObjUtil.isNotEmpty(startEditTime), "editTime", startEditTime);
        queryWrapper.lt(ObjUtil.isNotEmpty(endEditTime), "editTime", endEditTime);
        // JSON 数组查询
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        // 排序（白名单校验，防止 ORDER BY 注入）
        String safeSortField = SqlSortUtils.sanitizeSortField(sortField, "id", "userId", "spaceId", "picSize",
                "picWidth", "picHeight", "picScale", "name", "category", "reviewStatus", "reviewTime",
                "createTime", "editTime", "updateTime");
        queryWrapper.orderBy(StrUtil.isNotEmpty(safeSortField), "ascend".equals(sortOrder), safeSortField);
        return queryWrapper;
    }

    @Override
    public void checkPictureAuth(User loginUser, Picture picture) {
        Long spaceId = picture.getSpaceId();
        if (spaceId == null) {
            // 公共图库，仅本人或管理员可操作
            if (!picture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        } else {
            // 私有空间，仅空间管理员可操作
            if (!picture.getUserId().equals(loginUser.getId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }
    }

    @Override
    public void checkPictureAuth(User loginUser, Picture picture, String permission) {
        ThrowUtils.throwIf(loginUser == null || picture == null, ErrorCode.NO_AUTH_ERROR);
        Long spaceId = picture.getSpaceId();
        if (spaceId == null) {
            // 公共图库，仅本人或管理员可操作
            if (!picture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
            return;
        }
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // 私有空间仅属主/站点管理员，团队空间按数据库成员角色判断
        List<String> spacePermissions = spaceUserAuthManager.getPermissionList(space, loginUser);
        ThrowUtils.throwIf(!spacePermissions.contains(permission), ErrorCode.NO_AUTH_ERROR);
    }

    @Override
    public Page<Picture> listPictureVOByPage(PictureQueryRequest pictureQueryRequest, HttpServletRequest request) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 100, ErrorCode.PARAMS_ERROR);
        // 空间权限校验
        Long spaceId = pictureQueryRequest.getSpaceId();
        // 公开图库
        if (spaceId == null) {
            // 普通用户默认只能查看已过审的公开数据
            pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            pictureQueryRequest.setNullSpaceId(true);
        } else {
            // 空间图库：按目标空间显式判定查看权限（目标绑定，不依赖请求嗅探）
            checkSpaceViewPermission(spaceId, request);
        }

        // 查询数据库
        return this.page(new Page<>(current, size), this.getQueryWrapper(pictureQueryRequest));
    }

    @Override
    public List<PictureVO> searchPictureByColor(Long spaceId, String picColor, User loginUser) {
        // 1. 校验参数
        ThrowUtils.throwIf(spaceId == null || StrUtil.isBlank(picColor), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // 2. 校验空间权限
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        if (!loginUser.getId().equals(space.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间访问权限");
        }
        // 3. 查询该空间下所有图片（必须有主色调）
        List<Picture> pictureList = this.lambdaQuery()
                .eq(Picture::getSpaceId, spaceId)
                .isNotNull(Picture::getPicColor)
                .list();
        // 如果没有图片，直接返回空列表
        if (CollUtil.isEmpty(pictureList)) {
            return Collections.emptyList();
        }
        // 将目标颜色转为 Color 对象
        Color targetColor = Color.decode(picColor);
        // 4. 计算相似度并排序
        List<Picture> sortedPictures = pictureList.stream()
                .sorted(Comparator.comparingDouble(picture -> {
                    // 提取图片主色调
                    String hexColor = picture.getPicColor();
                    // 没有主色调的图片放到最后
                    if (StrUtil.isBlank(hexColor)) {
                        return Double.MAX_VALUE;
                    }
                    Color pictureColor = Color.decode(hexColor);
                    // 越大越相似
                    return -ColorSimilarUtils.calculateSimilarity(targetColor, pictureColor);
                }))
                // 取前 12 个
                .limit(12)
                .collect(Collectors.toList());

        // 转换为 PictureVO
        return sortedPictures.stream()
                .map(PictureVO::objToVo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editPictureByBatch(PictureEditByBatchRequest pictureEditByBatchRequest, User loginUser) {
        // UGC 文本清洗：剥离 HTML 标签防存储型 XSS
        pictureEditByBatchRequest.setCategory(TextSanitizeUtils.stripHtml(pictureEditByBatchRequest.getCategory()));
        pictureEditByBatchRequest.setTags(TextSanitizeUtils.stripHtmlList(pictureEditByBatchRequest.getTags()));
        pictureEditByBatchRequest.setNameRule(TextSanitizeUtils.stripHtml(pictureEditByBatchRequest.getNameRule()));
        List<Long> pictureIdList = pictureEditByBatchRequest.getPictureIdList();
        Long spaceId = pictureEditByBatchRequest.getSpaceId();
        String category = pictureEditByBatchRequest.getCategory();
        List<String> tags = pictureEditByBatchRequest.getTags();
        String nameRule = pictureEditByBatchRequest.getNameRule();

        // 1. 校验参数
        ThrowUtils.throwIf(spaceId == null || CollUtil.isEmpty(pictureIdList), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);

        // 2. 校验空间权限
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        if (!loginUser.getId().equals(space.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间访问权限");
        }

        // 3. 查询指定图片，仅选择需要的字段
        List<Picture> pictureList = this.lambdaQuery()
                .select(Picture::getId, Picture::getSpaceId)
                .eq(Picture::getSpaceId, spaceId)
                .in(Picture::getId, pictureIdList)
                .list();
        if (CollUtil.isEmpty(pictureList)) {
            return;
        }

        // 4. 更新分类和标签
        pictureList.forEach(picture -> {
            if (StrUtil.isNotBlank(category)) {
                picture.setCategory(category);
            }
            if (CollUtil.isNotEmpty(tags)) {
                picture.setTags(JSONUtil.toJsonStr(tags));
            }
        });

        // 4.5 词表同事务 upsert：本批实际写入的 tags/category 各计一次（不随图片数放大）
        if (CollUtil.isNotEmpty(tags)) {
            tagService.upsertVocabulary(tags, TagTypeEnum.TAG);
        }
        if (StrUtil.isNotBlank(category)) {
            tagService.upsertVocabulary(
                    Collections.singletonList(category), TagTypeEnum.CATEGORY);
        }

        // 5. 批量重命名
        fillPictureByNameRule(pictureList, nameRule);
        // 6. 操作数据库，批量更新
        boolean update = this.updateBatchById(pictureList);
        ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR);
        // 7. 清除列表缓存，避免批量改分类/标签/名称后列表脏读
        this.clearPictureListCache();
    }

    @Override
    public Picture getByUrl(String url) {
        return this.lambdaQuery()
                .eq(Picture::getUrl, url)
                .one();
    }

    /**
     * 根据命名规则批量重命名图片
     *
     * @param pictureList 图片列表
     * @param nameRule    命名规则
     */
    private void fillPictureByNameRule(List<Picture> pictureList, String nameRule) {
        if (CollUtil.isEmpty(pictureList) || StrUtil.isBlank(nameRule)) {
            return;
        }
        long count = 1;
        try {
            for (Picture picture : pictureList) {
                String newName = nameRule.replaceAll("\\{序号}", String.valueOf(count++));
                picture.setName(newName);
            }
        } catch (Exception e) {
            log.error("名称解析错误", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "名称解析错误");
        }
    }


    @Override
    public CreateOutPaintingTaskResponse createOutPaintingTask(CreatePictureOutPaintingTaskRequest createPictureOutPaintingTaskRequest, User loginUser) {
        // 获取图片信息
        Long pictureId = createPictureOutPaintingTaskRequest.getPictureId();
        Picture picture = Optional.ofNullable(this.getById(pictureId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在"));
        // 权限校验
        this.checkPictureAuth(loginUser, picture);

        // 幂等：同用户 + 同图片 + 同参数只允许一个任务，避免狂点创建出多个付费任务。
        // 先占位再调接口：占位成功才继续；占位失败说明已有任务在途或已创建
        String idempotentKey = buildOutPaintingIdempotentKey(loginUser.getId(), pictureId,
                createPictureOutPaintingTaskRequest.getParameters());
        Boolean claimed = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, OUT_PAINTING_PENDING, 1, TimeUnit.DAYS);
        if (!Boolean.TRUE.equals(claimed)) {
            String existing = stringRedisTemplate.opsForValue().get(idempotentKey);
            if (OUT_PAINTING_PENDING.equals(existing)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "相同参数的扩图任务正在创建中，请稍后查看任务状态");
            }
            log.info("命中扩图任务幂等键，复用已有任务, userId={}, pictureId={}, taskId={}",
                    loginUser.getId(), pictureId, existing);
            CreateOutPaintingTaskResponse.Output output = new CreateOutPaintingTaskResponse.Output();
            output.setTaskId(existing);
            CreateOutPaintingTaskResponse reused = new CreateOutPaintingTaskResponse();
            reused.setOutput(output);
            return reused;
        }

        String quotaKey = OUT_PAINTING_QUOTA_KEY + loginUser.getId() + ":" + LocalDate.now();
        try {
            // 配额只对"真正新建"的任务计数，幂等命中不扣额度
            consumeOutPaintingQuota(quotaKey);
            // 构建请求参数
            CreateOutPaintingTaskRequest taskRequest = new CreateOutPaintingTaskRequest();
            CreateOutPaintingTaskRequest.Input input = new CreateOutPaintingTaskRequest.Input();
            input.setImageUrl(picture.getUrl());
            taskRequest.setInput(input);
            BeanUtil.copyProperties(createPictureOutPaintingTaskRequest, taskRequest);
            // 创建任务
            CreateOutPaintingTaskResponse response = aliYunAiApi.createOutPaintingTask(taskRequest);
            if (response.getOutput() == null || StrUtil.isBlank(response.getOutput().getTaskId())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "扩图任务创建失败：未返回任务 id");
            }
            String taskId = response.getOutput().getTaskId();
            // 把占位改写成 taskId，后续同参数请求直接复用，不再重复调用付费接口
            stringRedisTemplate.opsForValue().set(idempotentKey, taskId, 1, TimeUnit.DAYS);
            // 记录任务归属，查询任务结果时校验（TTL 1 天，与任务生命周期相当）
            stringRedisTemplate.opsForValue().set(OUT_PAINTING_OWNER_KEY + taskId,
                    String.valueOf(loginUser.getId()), 1, TimeUnit.DAYS);
            return response;
        } catch (RuntimeException e) {
            // 创建失败（含超额、AI 报错）时释放占位并回滚配额，让用户可以真正重试；
            // CAS 删除只删 PENDING，已绑定 taskId 的记录不会被误删
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(idempotentKey),
                    OUT_PAINTING_PENDING);
            stringRedisTemplate.execute(QUOTA_ROLLBACK_SCRIPT, Collections.singletonList(quotaKey));
            throw e;
        }
    }

    /**
     * 构建扩图任务幂等键：用户 + 图片 + 参数指纹，三者一致即视为同一次提交
     */
    private String buildOutPaintingIdempotentKey(Long userId, Long pictureId,
                                                 CreateOutPaintingTaskRequest.Parameters parameters) {
        String paramsHash = DigestUtils.md5DigestAsHex(
                JSONUtil.toJsonStr(parameters).getBytes(StandardCharsets.UTF_8));
        return OUT_PAINTING_IDEMPOTENT_KEY + userId + ":" + pictureId + ":" + paramsHash;
    }

    /**
     * 扣减当日扩图配额：INCR 计数，首次创建时把 key 过期时间设到当天 24 点，避免跨天计数残留。
     * <p>
     * 超额只抛业务异常、本方法不做回滚：回滚统一放在调用方的 catch 里，
     * 否则"超额抛异常"和"外层回滚"会各减一次，把计数减成错的。
     */
    private void consumeOutPaintingQuota(String quotaKey) {
        Long used = stringRedisTemplate.opsForValue().increment(quotaKey);
        if (used != null && used == 1L) {
            Date expireAt = Date.from(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
            stringRedisTemplate.expireAt(quotaKey, expireAt);
        }
        if (used != null && used > outPaintingDailyQuota) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    String.format("今日扩图任务额度已用完（每日 %d 次），请明天再试", outPaintingDailyQuota));
        }
    }

    @Override
    public GetOutPaintingTaskResponse getOutPaintingTask(String taskId, User loginUser) {
        ThrowUtils.throwIf(StrUtil.isBlank(taskId), ErrorCode.PARAMS_ERROR, "任务 id 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        String owner = stringRedisTemplate.opsForValue().get(OUT_PAINTING_OWNER_KEY + taskId);
        ThrowUtils.throwIf(owner == null, ErrorCode.NOT_FOUND_ERROR, "任务不存在或归属记录已过期");
        ThrowUtils.throwIf(!owner.equals(String.valueOf(loginUser.getId())), ErrorCode.NO_AUTH_ERROR, "无权查看该任务");
        return aliYunAiApi.getOutPaintingTask(taskId);
    }

    // ==================== AI 打标（仅管理员，见 docs/plan.md T15） ====================

    /**
     * 出建议：**不写库**，只把逐张看图的结果返回给管理员确认。
     * <p>
     * 管理端入口（公共图库管理页）与助手侧若将来复用同一实现，都走这里——提示词与词表注入只有一份。
     * </p>
     */
    @Override
    public List<PictureAiTagSuggestionVO> suggestAiTags(PictureAiTagRequest pictureAiTagRequest, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        List<Picture> pictures = loadPicturesForAiTag(
                pictureAiTagRequest == null ? null : pictureAiTagRequest.getPictureIdList());
        // 模型没配就提前给一句能照做的文案，而不是发满 N 次注定失败的请求
        ThrowUtils.throwIf(!pictureAiTagManager.isConfigured(), ErrorCode.SYSTEM_ERROR,
                "AI 打标模型未配置：请设置 app.ai.vision 下的 base-url / api-key / model");
        List<PictureAiTagSuggestionVO> suggestions = pictureAiTagManager.suggest(pictures,
                tagService.listNamesByType(TagTypeEnum.TAG),
                tagService.listNamesByType(TagTypeEnum.CATEGORY));
        long okCount = suggestions.stream().filter(PictureAiTagSuggestionVO::isOk).count();
        log.info("AI 打标出建议：adminId={}, requested={}, suggested={}", loginUser.getId(), pictures.size(), okCount);
        return suggestions;
    }

    /**
     * 应用建议：把管理员确认过的标签/分类写进图库。
     * <p>
     * 三条口径：① **只写 tags 与 category**（更新条件里没有审核字段，见 {@link PictureAiTagManager#applyTo}）；
     * ② 空值不写（"这次不改"）；③ 词表 usageCount 按**本批去重后各计一次**，与
     * {@code editPictureByBatch} 的"不随图片数放大"同口径。
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<PictureAiTagSuggestionVO> applyAiTags(PictureAiTagApplyRequest pictureAiTagApplyRequest,
                                                      User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(pictureAiTagApplyRequest == null || CollUtil.isEmpty(pictureAiTagApplyRequest.getItems()),
                ErrorCode.PARAMS_ERROR, "请先选择要写入的图片");
        List<PictureAiTagApplyRequest.Item> items = pictureAiTagApplyRequest.getItems();
        ThrowUtils.throwIf(items.size() > pictureAiTagManager.getMaxPerRequest(), ErrorCode.PARAMS_ERROR,
                "单次最多写入 " + pictureAiTagManager.getMaxPerRequest() + " 张，请分批");
        Map<Long, Picture> pictureMap = new HashMap<>();
        List<Long> ids = items.stream()
                .map(PictureAiTagApplyRequest.Item::getPictureId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (CollUtil.isNotEmpty(ids)) {
            this.listByIds(ids).forEach(picture -> pictureMap.put(picture.getId(), picture));
        }
        List<PictureAiTagSuggestionVO> results = new ArrayList<>(items.size());
        Set<String> vocabularyTags = new LinkedHashSet<>();
        Set<String> vocabularyCategories = new LinkedHashSet<>();
        for (PictureAiTagApplyRequest.Item item : items) {
            Picture picture = item.getPictureId() == null ? null : pictureMap.get(item.getPictureId());
            if (picture == null) {
                results.add(applyFailed(item.getPictureId(), "图片不存在或已删除，请刷新后重试"));
                continue;
            }
            List<String> tags = PictureAiTagManager.sanitizeTags(item.getTags());
            String category = PictureAiTagManager.sanitizeCategory(item.getCategory());
            if (CollUtil.isEmpty(tags) && StrUtil.isBlank(category)) {
                results.add(applyFailed(picture.getId(), "没有要写入的标签或分类"));
                continue;
            }
            LambdaUpdateWrapper<Picture> update = Wrappers.<Picture>lambdaUpdate()
                    .eq(Picture::getId, picture.getId());
            PictureAiTagManager.applyTo(update, tags, category);
            boolean updated = this.update(update);
            if (!updated) {
                results.add(applyFailed(picture.getId(), "写入失败，请重试"));
                continue;
            }
            vocabularyTags.addAll(tags);
            if (StrUtil.isNotBlank(category)) {
                vocabularyCategories.add(category);
            }
            PictureAiTagSuggestionVO result = new PictureAiTagSuggestionVO();
            result.setPictureId(picture.getId());
            result.setUrl(picture.getUrl());
            result.setOk(true);
            // 回显**实际写入**的内容（写与不写由 applyTo 的"空值不写"规则决定）
            result.setTags(CollUtil.isEmpty(tags) ? parseTags(picture.getTags()) : tags);
            result.setCategory(StrUtil.isBlank(category) ? StrUtil.blankToDefault(picture.getCategory(), "") : category);
            result.setMessage("已写入图库");
            results.add(result);
        }
        // 词表同事务 upsert：本批实际写入的词各计一次（与 editPictureByBatch 同口径）
        if (CollUtil.isNotEmpty(vocabularyTags)) {
            tagService.upsertVocabulary(new ArrayList<>(vocabularyTags), TagTypeEnum.TAG);
        }
        if (CollUtil.isNotEmpty(vocabularyCategories)) {
            tagService.upsertVocabulary(new ArrayList<>(vocabularyCategories), TagTypeEnum.CATEGORY);
        }
        clearPictureListCache();
        long written = results.stream().filter(PictureAiTagSuggestionVO::isOk).count();
        log.info("AI 打标落库：adminId={}, requested={}, written={}", loginUser.getId(), items.size(), written);
        return results;
    }

    /** 取待打标的图片：按请求顺序返回（管理页要逐条对应），有 id 不存在即拒 */
    private List<Picture> loadPicturesForAiTag(List<Long> pictureIdList) {
        ThrowUtils.throwIf(CollUtil.isEmpty(pictureIdList), ErrorCode.PARAMS_ERROR, "请先选择要打标的图片");
        List<Long> ids = pictureIdList.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());
        ThrowUtils.throwIf(CollUtil.isEmpty(ids), ErrorCode.PARAMS_ERROR, "请先选择要打标的图片");
        ThrowUtils.throwIf(ids.size() > pictureAiTagManager.getMaxPerRequest(), ErrorCode.PARAMS_ERROR,
                "单次最多 " + pictureAiTagManager.getMaxPerRequest() + " 张，请分批");
        Map<Long, Picture> pictureMap = new HashMap<>();
        this.listByIds(ids).forEach(picture -> pictureMap.put(picture.getId(), picture));
        ThrowUtils.throwIf(pictureMap.size() != ids.size(), ErrorCode.NOT_FOUND_ERROR,
                "部分图片不存在或已删除，请刷新后重试");
        return ids.stream().map(pictureMap::get).collect(Collectors.toList());
    }

    /** 打标结果里的失败条目 */
    private PictureAiTagSuggestionVO applyFailed(Long pictureId, String message) {
        PictureAiTagSuggestionVO result = new PictureAiTagSuggestionVO();
        result.setPictureId(pictureId);
        result.setOk(false);
        result.setMessage(message);
        return result;
    }

    /** 库里存的 tags 是 JSON 数组字符串，回显时转回列表 */
    private List<String> parseTags(String tagsJson) {
        if (StrUtil.isBlank(tagsJson)) {
            return new ArrayList<>();
        }
        try {
            return JSONUtil.toList(tagsJson, String.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
