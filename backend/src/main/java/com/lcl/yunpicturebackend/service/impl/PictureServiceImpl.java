package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
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
import com.lcl.yunpicturebackend.domain.po.SpaceUser;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureAiTagSuggestionVO;
import com.lcl.yunpicturebackend.domain.vo.PictureTrashResultVO;
import com.lcl.yunpicturebackend.domain.vo.PictureTrashVO;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import com.lcl.yunpicturebackend.domain.vo.UserVO;
import com.lcl.yunpicturebackend.domain.vo.VOutPaintingStatusVO;
import com.lcl.yunpicturebackend.enums.PictureReviewStatusEnum;
import com.lcl.yunpicturebackend.enums.TagTypeEnum;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.config.CosClientConfig;
import com.lcl.yunpicturebackend.manager.CosManager;
import com.lcl.yunpicturebackend.manager.ai.PictureAiTagManager;
import com.lcl.yunpicturebackend.manager.auth.SpaceUserAuthManager;
import com.lcl.yunpicturebackend.manager.auth.LoginSessionValidator;
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
import com.lcl.yunpicturebackend.service.ISpaceUserService;
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
import java.time.LocalDateTime;
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
     * 维护态文案（T64）：服务端抛出的异常与前端维护通知**共用这一句**，避免两处各写一句。
     * 口径见 docs/decisions.md 2026-10-16。
     */
    public static final String OUT_PAINTING_MAINTENANCE_MESSAGE =
            "AI 扩图功能维护中：厂商扩图模型已下线，暂时无法生成，恢复后本页会自动重新开放";

    /**
     * 图片列表缓存版本号的 Redis key 前缀。实际 key 按 scope 分开（见 {@link #cacheVersionKey}）：
     * 空间图库是该空间 id，公共图库是 {@link #CACHE_SCOPE_PUBLIC}。
     */
    private static final String PICTURE_LIST_CACHE_VERSION_KEY_PREFIX = "huoshantuku:listPictureVOByPage:version";

    /**
     * 公共图库（spaceId 为空）的缓存 scope 名。
     * 用字符串而不是 0，避免与"空间 id 恰好是 0"撞成同一个 scope。
     */
    static final String CACHE_SCOPE_PUBLIC = "public";

    /**
     * 来源地址最大长度：与 backend/sql/add_picture_source_url.sql 里 {@code sourceUrl varchar(1024)} 对齐。
     * 超长时严格模式的 INSERT 会报 "Data too long"，而入库是整批原子的（T28）——
     * 批量抓取里只要有一张超长，整批 30 张都会回滚并补偿删除，所以必须在发起下载之前就拦住（T31）。
     */
    private static final int MAX_SOURCE_URL_LENGTH = 1024;

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

    /**
     * 扩图维护门闩（T64，默认关闭＝维护态）：厂商模型已下线、替代模型免费额度已作废。
     * <p>
     * 关闭时两个入口一律返回维护提示，不调百炼、不扣配额、不写幂等键；默认 false 是失败安全——
     * 漏配环境变量只会让功能不可用，不会开始按量计费。恢复只需把它配成 true（前端无需发版）。
     */
    @Value("${app.outpainting.enabled:false}")
    private boolean outPaintingEnabled;

    private final IUserService userService;

    private final ISpaceService spaceService;

    private final ITagService tagService;

    private final SpaceUserAuthManager spaceUserAuthManager;

    /** 写操作前的双登录态一致性校验（T56），见 {@link LoginSessionValidator} */
    private final LoginSessionValidator loginSessionValidator;

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

    /** 回收站列表要按空间成员角色过滤（团队空间里"我有 picture:delete 的空间"），故需查成员关系表 */
    @Resource
    private ISpaceUserService spaceUserService;
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

        // URL 来源的图片按"源地址 + 目标空间"判重：同一张图在同一空间里只导一次（跨空间各存一份是允许的）
        if (inputSource instanceof String) {
            String sourceUrl = (String) inputSource;
            Picture existingPicture = this.getBySourceUrl(sourceUrl, spaceId, pictureId);
            if (existingPicture != null) {
                log.warn("图片已存在，源地址: {}, 空间: {}, 已有ID: {}", sourceUrl, spaceId, existingPicture.getId());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片已存在");
            }
        }
        // 上传图片，获取信息：网络阶段（下载/读取输入源 → 转存 COS → 构造未落库记录）单独成方法，
        // 与批量抓取共用；本方法负责把它落库
        Picture picture = preparePicture(inputSource, pictureUploadRequest, loginUser, spaceId, oldPicture);
        // 开启事务
        Long finalSpaceId = spaceId;
        Picture finalOldPicture = oldPicture;
        try {
            transactionTemplate.execute(status -> {
                boolean result = this.saveOrUpdate(picture);
                ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片上传失败");
                // 用文件替换掉的记录不再有"来源地址"：MyBatis-Plus 的 updateById 不写 null 字段，
                // 必须显式清空，否则旧源地址会一直留着，把同地址的重新导入误挡掉
                if (picture.getSourceUrl() == null && finalOldPicture != null
                        && StrUtil.isNotBlank(finalOldPicture.getSourceUrl())) {
                    this.lambdaUpdate()
                            .eq(Picture::getId, picture.getId())
                            .set(Picture::getSourceUrl, null)
                            .update();
                }
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
        // 清除缓存：本次写入只影响这张图所在的空间（finalSpaceId 为空即公共图库）
        this.clearPictureListCache(cacheScopeOf(finalSpaceId));
        return PictureVO.objToVo(picture);
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    class UploadResult {
        private int index;
        private String url;
        private boolean success;
        /**
         * 已转存 COS、尚未落库的记录；成功时非空
         */
        private Picture picture;
        private String errorMessage;
    }

    /**
     * 图片上传的网络阶段：把输入源下载/读取后转存 COS，返回**尚未落库**的记录。
     * <p>
     * 单张上传与批量抓取共用：单张上传随即落库；批量抓取让并行线程只跑这一段，
     * 等 allOf 归集后一次性批量入库——避免 30 张图 30 个事务、30 次缓存失效。
     * <p>
     * protected 是为了让集成测试能用 @SpyBean 替换掉真实下载（与 {@link #loadFromDb} 同一做法）。
     *
     * @param inputSource           图片来源（MultipartFile 或 URL 字符串）
     * @param pictureUploadRequest  上传请求，用于取图片名称
     * @param loginUser             上传人
     * @param spaceId               目标空间，null 表示公共图库
     * @param oldPicture            替换图片时的原记录，新增时为 null
     * @return 未落库的图片记录
     */
    protected Picture preparePicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser,
                                     Long spaceId, Picture oldPicture) {
        // 来源地址长度前置校验：超长就按参数错误拒绝，不发起下载、不转存 COS。
        // 放在这里是因为单张上传与批量抓取共用本方法——批量下这次失败只让该张跳过（逐张隔离），
        // 不会像"入库时才发现超长"那样把整批一起打掉（T31 / 独立 review ①-1）
        ThrowUtils.throwIf(inputSource instanceof String
                        && ((String) inputSource).length() > MAX_SOURCE_URL_LENGTH,
                ErrorCode.PARAMS_ERROR, "来源地址过长");
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
        // 填充来源地址：只有 URL 导入（单张 /upload/url 与批量抓取）才有可判重的来源，文件上传为 null
        picture.setSourceUrl(inputSource instanceof String ? (String) inputSource : null);
        // 填充图片颜色
        picture.setPicColor(uploadPictureResult.getPicColor());
        // 填充审核信息
        fillReviewParams(picture, loginUser);
        return picture;
    }

    /**
     * 按"源地址 + 目标空间"查已导入的图片。
     * <p>
     * 源地址只对 URL 导入（单张 {@code /picture/upload/url} 与批量抓取）有意义，文件上传写入 null，
     * 因此这条检查只覆盖 URL 路径。同一张图进不同空间是允许的，所以判重带空间条件
     * （{@code spaceId} 为 null 表示公共图库，用 IS NULL 匹配）。
     * <p>
     * 替换图片时排除自身：否则"用同一个源地址替换自己"会被自己挡住。
     * 用 {@code getOne(wrapper, false)} 而不是 {@code one()}：源地址列不是唯一索引（刻意不建，
     * 逻辑删除的行会永久占住唯一索引），理论上可能存在同一地址的多条记录，这里取第一条即可。
     *
     * @param sourceUrl 来源地址
     * @param spaceId   目标空间，null 表示公共图库
     * @param excludeId 替换图片时要排除的记录 id，新增时为 null
     * @return 已存在的记录，没有则返回 null
     */
    private Picture getBySourceUrl(String sourceUrl, Long spaceId, Long excludeId) {
        LambdaQueryWrapper<Picture> wrapper = Wrappers.lambdaQuery(Picture.class)
                .eq(Picture::getSourceUrl, sourceUrl)
                .eq(spaceId != null, Picture::getSpaceId, spaceId)
                .isNull(spaceId == null, Picture::getSpaceId)
                .ne(excludeId != null, Picture::getId, excludeId);
        return this.getOne(wrapper, false);
    }

    /**
     * 抓取 Bing 图片搜索结果页的 HTML。
     * <p>
     * protected 是为了让集成测试能用 @SpyBean 替换掉真实外网请求（与 {@link #loadFromDb} 同一做法）：
     * "一次入库、一次清缓存、失败补偿"这些语义只有在没有外网的测试里才能稳定复现。
     *
     * @param searchText 搜索词
     * @param offset     结果偏移量
     * @return 页面 HTML
     */
    protected String fetchBingSearchPage(String searchText, int offset) {
        String fetchURL = String.format("https://cn.bing.com/images/async?q=%s&first=%d&mmasync=1",
                searchText, offset + 10);
        try {
            Document document = Jsoup.connect(fetchURL)
                    // 出站请求带上 traceId：外部依赖超时/改版时，可以和本地日志串起来
                    .header(TraceContext.TRACE_ID_HEADER, TraceContext.currentOrDefault())
                    .get();
            return document.html();
        } catch (IOException e) {
            log.error("获取页面失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取页面失败");
        }
    }

    @Override
    public int uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser) {
        String searchText = pictureUploadByBatchRequest.getSearchText();
        String namePrefix = pictureUploadByBatchRequest.getNamePrefix();
        Integer offset = pictureUploadByBatchRequest.getOffset();
        if (StrUtil.isBlank(namePrefix)) {
            namePrefix = searchText;
        }
        ThrowUtils.throwIf(pictureUploadByBatchRequest.getCount() == null, ErrorCode.PARAMS_ERROR, "图片数量不能为空");
        ThrowUtils.throwIf(pictureUploadByBatchRequest.getCount() > 30, ErrorCode.PARAMS_ERROR, "图片数量不能超过30张");

        String html = fetchBingSearchPage(searchText, offset);
        // 解析原图地址：选择器与字段名走配置（app.bing-image.*），
        // 契约由 BingImageParserContractTest 用固定样本钉住，Bing 改版时构建期就能暴露
        List<String> imageUrls = bingImageParser.parseImageUrls(html,
                pictureUploadByBatchRequest.getCount());
        // 去重提到并行之前：批内重复的源地址只下载一次，库中已存在的地址一次 IN 查询拦掉
        List<Map.Entry<Integer, String>> indexedUrls = filterDuplicateAndMissingUrls(imageUrls);
        if (indexedUrls.isEmpty()) {
            log.info("批量上传完成，总数: 0, 成功: 0, 失败: 0");
            return 0;
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

                            // 并行阶段只做网络（下载 + 转存 COS）并构造记录，不写库、不清缓存；
                            // 批量抓取一律进公共图库（请求体没有 spaceId），故不走空间权限与额度分支
                            result.setPicture(preparePicture(fileURL, pictureUploadRequest, loginUser, null, null));
                            result.setSuccess(true);
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

        List<Picture> picturesToSave = results.stream()
                .filter(UploadResult::isSuccess)
                .map(UploadResult::getPicture)
                .collect(Collectors.toList());
        // 落库只做一次（一个事务），失败整批回滚并补偿删掉已转存的文件
        persistPicturesInBatch(picturesToSave);
        if (CollUtil.isNotEmpty(picturesToSave)) {
            // 清缓存也只做一次：逐张清会在批量执行期间把列表缓存反复打穿
            // 批量抓取一律进公共图库（请求体没有 spaceId），所以只失效 public 这一个 scope
            this.clearPictureListCache(CACHE_SCOPE_PUBLIC);
            log.info("批量入库成功，图片 ID: {}",
                    picturesToSave.stream().map(Picture::getId).collect(Collectors.toList()));
        }

        long failCount = results.size() - picturesToSave.size();

        log.info("批量上传完成，总数: {}, 成功: {}, 失败: {}", results.size(), picturesToSave.size(), failCount);

        results.forEach(result -> {
            if (!result.isSuccess()) {
                log.warn("图片上传失败 [{}], URL: {}, 原因: {}",
                        result.getIndex(), result.getUrl(), result.getErrorMessage());
            }
        });

        return picturesToSave.size();
    }

    /**
     * 批量落库：一个事务内 saveBatch（本地 JDBC 已带 rewriteBatchedStatements=true，合并成多值 INSERT），
     * 取代改动前"每张图各开一个事务、各自提交"。
     * <p>
     * 入库阶段是整批原子的：任一行失败即整批回滚，此时这一批已经转存到 COS 的文件不再被任何记录引用，
     * 逐个补偿删除（cleanupPictureFile 按引用计数兜底，不会误删仍被占用的文件）。
     *
     * @param pictures 待落库的记录
     */
    private void persistPicturesInBatch(List<Picture> pictures) {
        if (CollUtil.isEmpty(pictures)) {
            return;
        }
        try {
            transactionTemplate.execute(status -> {
                boolean saved = this.saveBatch(pictures);
                ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "图片批量入库失败");
                return pictures;
            });
        } catch (RuntimeException e) {
            pictures.forEach(this::cleanupPictureFile);
            throw e;
        }
    }

    /**
     * 并行抓取前的地址过滤：批内按源地址去重（Bing 结果去掉查询参数后常有重复，重复项会被下载两次
     * 并在库里留下两条内容相同的记录），并一次 IN 查询拦掉**公共图库里已导入过**的源地址
     * （批量抓取一律进公共图库，所以空间条件是 IS NULL；T29 起 sourceUrl 才是判重依据，
     * 改动前每张图一次 getByUrl 且比对的是 COS 地址、永远命中不了）。
     * <p>
     * 序号保留解析出的原始位置，命名前缀不因跳过而重排。
     *
     * @param imageUrls 解析出的原图地址
     * @return 原始序号 + 地址，按解析顺序
     */
    private List<Map.Entry<Integer, String>> filterDuplicateAndMissingUrls(List<String> imageUrls) {
        if (CollUtil.isEmpty(imageUrls)) {
            return Collections.emptyList();
        }
        Map<String, Integer> urlToIndex = new LinkedHashMap<>();
        for (int i = 0; i < imageUrls.size(); i++) {
            urlToIndex.putIfAbsent(imageUrls.get(i), i);
        }
        Set<String> existingUrls = this.lambdaQuery()
                .select(Picture::getSourceUrl)
                .in(Picture::getSourceUrl, urlToIndex.keySet())
                .isNull(Picture::getSpaceId)
                .list().stream()
                .map(Picture::getSourceUrl)
                .collect(Collectors.toSet());
        List<Map.Entry<Integer, String>> indexedUrls = new ArrayList<>(urlToIndex.size());
        urlToIndex.forEach((url, index) -> {
            if (!existingUrls.contains(url)) {
                indexedUrls.add(new AbstractMap.SimpleEntry<>(index, url));
            }
        });
        return indexedUrls;
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
        // 清除缓存：审核改的是这张图的可见性，只影响它所在的空间
        this.clearPictureListCache(cacheScopeOf(oldPicture.getSpaceId()));
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
        // Redis key 携带版本号，且版本号按 scope（空间 / 公共图库）分开：
        // 清理缓存时只需把该 scope 的版本号 +1，旧 key 靠 TTL 自然过期；
        // 写一个空间不会连累其它空间与公共图库的缓存
        long cacheVersion = getListCacheVersion(cacheScopeOf(pictureQueryRequest.getSpaceId()));
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
     * 列表缓存失效的 scope：空间图库是该空间 id，公共图库是 {@link #CACHE_SCOPE_PUBLIC}。
     * <p>
     * 读路径从查询请求的 spaceId 推导、写路径从被写图片的 spaceId 推导，两侧共用本方法，
     * 避免读写口径不一致导致"该失效的没失效"。
     * <p>
     * 之所以能按空间切分：查询的 scope 两两不相交（spaceId 为空时只查公共图库，不会跨空间），
     * 且图片的空间归属创建后不可变（编辑接口没有 spaceId 字段；替换时空间不一致直接拒）
     * ——所以一次写入最多只影响一个 scope。
     */
    static String cacheScopeOf(Long spaceId) {
        return spaceId == null ? CACHE_SCOPE_PUBLIC : String.valueOf(spaceId);
    }

    /**
     * 某个 scope 的版本号 key。
     * <p>
     * 刻意不设 TTL：计数器一旦过期归零，上一世代的旧 key 会重新变得可达，等于复活陈旧数据。
     */
    static String cacheVersionKey(String scope) {
        return PICTURE_LIST_CACHE_VERSION_KEY_PREFIX + ":" + scope;
    }

    /**
     * 清空单个 scope 的图片列表缓存（绝大多数写路径只影响一个空间）
     */
    private void clearPictureListCache(String scope) {
        clearPictureListCache(Collections.singleton(scope));
    }

    /**
     * 清空图片列表缓存
     *
     * @param scopes 本次写入影响的 scope，见 {@link #cacheScopeOf}
     */
    private void clearPictureListCache(Collection<String> scopes) {
        // 事务提交后再失效缓存，避免"清缓存 → 并发请求回源读到未提交旧数据 → 旧数据写回缓存"的竞态
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doClearPictureListCache(scopes);
                }
            });
        } else {
            doClearPictureListCache(scopes);
        }
    }

    /**
     * 实际清理图片列表缓存：逐个 scope 自增版本号，再清本实例本地缓存并广播。
     * <p>
     * 版本号 +1 使该 scope 所有旧版本 key 立即失效，旧 key 靠 TTL 自然过期
     * （避免使用 KEYS 通配符全库扫描阻塞 Redis）。
     * <p>
     * 本地层仍是全清：本地缓存不按空间分片，这是既定取舍（见 docs/features/F4-图片列表多级缓存.md）。
     * 因此广播报文也不必带 scope——对端收到就是清空自己整个 L1，不需要知道是哪个空间。
     */
    private void doClearPictureListCache(Collection<String> scopes) {
        for (String scope : scopes) {
            stringRedisTemplate.opsForValue().increment(cacheVersionKey(scope));
        }
        // 先自增版本号再广播：其它实例收到广播清空本地缓存后回源，读到的是新版本 key，
        // 不会把旧数据写回本地。广播失败时由本地 TTL（5~10 分钟）兜底
        pictureListCacheInvalidator.invalidateAll();
    }

    /**
     * 获取某个 scope 的图片列表缓存版本号
     */
    private long getListCacheVersion(String scope) {
        String version = stringRedisTemplate.opsForValue().get(cacheVersionKey(scope));
        return version != null ? Long.parseLong(version) : 0L;
    }

    @Override
    @Transactional
    public void deletePicture(DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 写操作：先校验 Spring Session 与 Sa-Token 同属一人（T56），再逐张复核权限
        User loginUser = loginSessionValidator.requireConsistentLogin(request);
        long pictureId = deleteRequest.getId();
        // 判断是否存在
        Picture oldPicture = getById(pictureId);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 校验权限
        this.checkPictureAuth(loginUser, oldPicture, SpaceUserPermissionConstant.PICTURE_DELETE);
        // 开启事务
        transactionTemplate.execute(status -> {
            // 操作数据库：软删进回收站（isDelete=1 + deleteTime=now），COS 文件留到彻底删除时再清
            int affected = getBaseMapper().softDeleteById(pictureId);
            ThrowUtils.throwIf(affected != 1, ErrorCode.OPERATION_ERROR);
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
        // COS 原件与缩略图**不再即时清理**（T50）：图片进了回收站、恢复后还要用，
        // 留到"彻底删除"或保留期清理时统一清（引用计数为 0 才删）。
        // 清除缓存：删除只影响这张图所在的空间
        clearPictureListCache(cacheScopeOf(oldPicture.getSpaceId()));
    }

    // ==================== 回收站（T50） ====================

    @Override
    public Page<PictureTrashVO> listTrash(PictureTrashQueryRequest queryRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        long current = queryRequest.getCurrent();
        long size = queryRequest.getPageSize();
        // 与图片列表同口径：限制单页大小防爬
        ThrowUtils.throwIf(size > 100, ErrorCode.PARAMS_ERROR);
        boolean admin = userService.isAdmin(loginUser);
        Collection<Long> spaceIds = admin ? Collections.emptyList() : listTrashVisibleSpaceIds(loginUser);
        IPage<Picture> page = getBaseMapper()
                .selectTrashPage(new Page<>(current, size), admin, loginUser.getId(), spaceIds);
        Page<PictureTrashVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(PictureTrashVO::of).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public List<PictureTrashResultVO> restorePictures(PictureTrashBatchRequest batchRequest,
                                                      HttpServletRequest request) {
        // 写操作：先校验 Spring Session 与 Sa-Token 同属一人（T56），再逐张复核权限
        User loginUser = loginSessionValidator.requireConsistentLogin(request);
        List<Long> ids = validatedTrashIds(batchRequest);
        List<PictureTrashResultVO> results = new ArrayList<>(ids.size());
        Set<String> touchedScopes = new HashSet<>();
        for (Long id : ids) {
            results.add(restoreOne(id, loginUser, touchedScopes));
        }
        if (!touchedScopes.isEmpty()) {
            clearPictureListCache(touchedScopes);
        }
        return results;
    }

    @Override
    public List<PictureTrashResultVO> purgePictures(PictureTrashBatchRequest batchRequest,
                                                    HttpServletRequest request) {
        // 写操作：先校验 Spring Session 与 Sa-Token 同属一人（T56），再逐张复核权限
        User loginUser = loginSessionValidator.requireConsistentLogin(request);
        List<Long> ids = validatedTrashIds(batchRequest);
        List<PictureTrashResultVO> results = new ArrayList<>(ids.size());
        Set<String> touchedScopes = new HashSet<>();
        for (Long id : ids) {
            results.add(purgeOne(id, loginUser, touchedScopes));
        }
        if (!touchedScopes.isEmpty()) {
            clearPictureListCache(touchedScopes);
        }
        return results;
    }

    /**
     * 回收站可见的空间集合：我拥有的空间 + 我加入的、成员角色含 picture:delete 的团队空间。
     * 公共图库（spaceId 为空）里本人上传的由图库那条 SQL 单独覆盖，不进这个集合。
     */
    private Collection<Long> listTrashVisibleSpaceIds(User loginUser) {
        Set<Long> candidates = new LinkedHashSet<>();
        spaceService.lambdaQuery().eq(Space::getUserId, loginUser.getId()).list()
                .forEach(space -> candidates.add(space.getId()));
        spaceUserService.lambdaQuery().eq(SpaceUser::getUserId, loginUser.getId()).list()
                .forEach(member -> candidates.add(member.getSpaceId()));
        List<Long> visible = new ArrayList<>();
        for (Long spaceId : candidates) {
            Space space = spaceService.getById(spaceId);
            if (space == null) {
                continue;
            }
            if (spaceUserAuthManager.getPermissionList(space, loginUser)
                    .contains(SpaceUserPermissionConstant.PICTURE_DELETE)) {
                visible.add(spaceId);
            }
        }
        return visible;
    }

    private List<Long> validatedTrashIds(PictureTrashBatchRequest batchRequest) {
        ThrowUtils.throwIf(batchRequest == null || CollUtil.isEmpty(batchRequest.getPictureIdList()),
                ErrorCode.PARAMS_ERROR, "pictureIdList 不能为空");
        List<Long> ids = batchRequest.getPictureIdList();
        ThrowUtils.throwIf(ids.size() > 50, ErrorCode.PARAMS_ERROR, "单次最多操作 50 张");
        ThrowUtils.throwIf(ids.stream().anyMatch(id -> id == null || id <= 0), ErrorCode.PARAMS_ERROR);
        return ids;
    }

    /**
     * 恢复单张：先复核权限，再在**同一事务**内"恢复记录 + 回补空间名额"。
     * <p>
     * 配额条件写在 UPDATE 的 WHERE 里（T57）：并发恢复（或恢复与上传同时发生）时不拿 Java 侧读到的快照做判断，
     * 由数据库保证 `totalCount / totalSize` 不越上限——条件不成立就是更新 0 行，抛异常让整笔事务回滚，
     * 记录留在回收站。逐张返回结果——一张失败不影响同批其它张。
     */
    private PictureTrashResultVO restoreOne(Long id, User loginUser, Set<String> touchedScopes) {
        Picture picture = getBaseMapper().selectTrashedById(id);
        if (picture == null) {
            return PictureTrashResultVO.fail(id, "记录不存在或不在回收站");
        }
        try {
            checkPictureAuth(loginUser, picture, SpaceUserPermissionConstant.PICTURE_DELETE);
        } catch (BusinessException e) {
            // checkPictureAuth 对空间图会先查空间：查不到抛 40400（空间已被删），否则是权限不足
            String message = e.getCode() == ErrorCode.NOT_FOUND_ERROR.getCode()
                    ? "原空间已不存在，无法恢复"
                    : "没有恢复该图片的权限";
            return PictureTrashResultVO.fail(id, message);
        }
        Long spaceId = picture.getSpaceId();
        try {
            if (spaceId == null) {
                // 公共图库：不占空间名额，直接恢复
                ThrowUtils.throwIf(getBaseMapper().restoreTrashedById(id) != 1,
                        ErrorCode.OPERATION_ERROR, "恢复失败");
            } else {
                long picSize = nvl(picture.getPicSize());
                // checkPictureAuth 已确认空间存在（查不到就抛 40400 了）
                // 恢复 + 名额回补放在一个事务里，避免中途失败留下"已恢复但没记名额"的脏账
                transactionTemplate.execute(status -> {
                    ThrowUtils.throwIf(getBaseMapper().restoreTrashedById(id) != 1,
                            ErrorCode.OPERATION_ERROR, "恢复失败");
                    if (!accumulateSpaceQuota(spaceId, picSize)) {
                        throw new BusinessException(ErrorCode.OPERATION_ERROR,
                                restoreQuotaExceededMessage(spaceId, picSize));
                    }
                    return true;
                });
            }
        } catch (BusinessException e) {
            log.warn("恢复图片失败, pictureId={}, msg={}", id, e.getMessage());
            return PictureTrashResultVO.fail(id, e.getMessage());
        }
        touchedScopes.add(cacheScopeOf(spaceId));
        return PictureTrashResultVO.ok(id, "已恢复");
    }

    /**
     * 事务内回补空间名额：**配额条件放进 UPDATE 的 WHERE**（与上传路径同口径），返回 false 表示额度不足。
     * <p>
     * 不能"先读空间快照再判断、然后无条件加"：并发恢复（或与上传同时发生）会各自读到同一个旧快照一起通过，
     * 最后把计数加超上限（T57）。条件放进 WHERE 后由数据库串行化判定，条件不成立时影响 0 行。
     * `maxCount / maxSize` 为空视为不限；`totalCount / totalSize` 用 COALESCE 兜住历史 null。
     */
    private boolean accumulateSpaceQuota(Long spaceId, long picSize) {
        return spaceService.lambdaUpdate()
                .eq(Space::getId, spaceId)
                .apply("(maxCount IS NULL OR COALESCE(totalCount, 0) + 1 <= maxCount)")
                .apply("(maxSize IS NULL OR COALESCE(totalSize, 0) + {0} <= maxSize)", picSize)
                .setSql("totalCount = COALESCE(totalCount, 0) + 1")
                .setSql("totalSize = COALESCE(totalSize, 0) + " + picSize)
                .update();
    }

    /**
     * 额度不足时给用户看哪条：回读一次空间区分"数量触顶 / 容量触顶"。
     * <p>
     * **只影响文案**——判定以 {@link #accumulateSpaceQuota} 的 UPDATE 条件为准；万一两边不一致（例如刚被并发改过），
     * 也只是回落到通用文案，不会影响"是否该拒绝"这个结论。
     */
    private String restoreQuotaExceededMessage(Long spaceId, long picSize) {
        Space space = spaceService.getById(spaceId);
        if (space == null) {
            return "空间额度不足，无法恢复";
        }
        if (space.getMaxCount() != null && nvl(space.getTotalCount()) + 1 > space.getMaxCount()) {
            return "空间图片数量已达上限，无法恢复";
        }
        if (space.getMaxSize() != null && nvl(space.getTotalSize()) + picSize > space.getMaxSize()) {
            return "空间容量已达上限，无法恢复";
        }
        return "空间额度不足，无法恢复";
    }

    /**
     * 彻底删除单张：先复核权限，物理删行，再按引用计数清 COS（同一 URL 还有存活引用时不动）。
     * 名额在进回收站时已经扣过，这里不再碰空间计数。
     */
    private PictureTrashResultVO purgeOne(Long id, User loginUser, Set<String> touchedScopes) {
        Picture picture = getBaseMapper().selectTrashedById(id);
        if (picture == null) {
            return PictureTrashResultVO.fail(id, "记录不存在或不在回收站");
        }
        try {
            checkPictureAuth(loginUser, picture, SpaceUserPermissionConstant.PICTURE_DELETE);
        } catch (BusinessException e) {
            return PictureTrashResultVO.fail(id, "没有彻底删除该图片的权限");
        }
        int purged;
        try {
            purged = getBaseMapper().purgeTrashedById(id);
        } catch (RuntimeException e) {
            log.warn("彻底删除图片失败, pictureId={}", id, e);
            return PictureTrashResultVO.fail(id, "彻底删除失败");
        }
        if (purged != 1) {
            return PictureTrashResultVO.fail(id, "记录不存在或不在回收站");
        }
        // 引用计数为 0 才真正删 COS（cleanupPictureFile 内部按 url 统计存活引用）
        cleanupPictureFile(picture);
        touchedScopes.add(cacheScopeOf(picture.getSpaceId()));
        return PictureTrashResultVO.ok(id, "已彻底删除");
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    @Override
    public int purgeExpiredTrash(int retentionDays, int limit) {
        Date deadline = Date.from(LocalDateTime.now().minusDays(retentionDays)
                .atZone(ZoneId.systemDefault()).toInstant());
        List<Picture> expired = getBaseMapper().selectExpiredTrash(deadline, limit);
        int purged = 0;
        Set<String> touchedScopes = new HashSet<>();
        for (Picture picture : expired) {
            try {
                if (getBaseMapper().purgeTrashedById(picture.getId()) == 1) {
                    cleanupPictureFile(picture);
                    touchedScopes.add(cacheScopeOf(picture.getSpaceId()));
                    purged++;
                }
            } catch (RuntimeException e) {
                // 单张失败不中断整批；下一轮扫描还会再遇到它
                log.warn("清理过期回收站图片失败, pictureId={}", picture.getId(), e);
            }
        }
        if (!touchedScopes.isEmpty()) {
            clearPictureListCache(touchedScopes);
        }
        return purged;
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
        // 清除缓存：scope 必须取旧记录——新实体是按 DTO 拷的，其 spaceId 恒为 null，
        // 拿它推导会把空间图库写成公共图库，导致该空间的列表不刷新
        this.clearPictureListCache(cacheScopeOf(oldPicture.getSpaceId()));
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
        // 清除缓存：同上，scope 取旧记录的 spaceId（新实体按 DTO 拷，spaceId 恒为 null）
        this.clearPictureListCache(cacheScopeOf(oldPicture.getSpaceId()));
    }

    @Override
    public long countUrlReferences(String url) {
        return getBaseMapper().countUrlReferences(url);
    }

    /**
     * 按引用计数异步清理 COS 中的图片文件，仅当该 URL 已无任何存活/可恢复记录引用时才真正删除。
     * <p>
     * 三类调用前提：记录已删除、记录已改指向新 URL、事务回滚后清理刚上传的孤儿文件（T3.11）——
     * 三种情形下 count 都只含其它引用，count > 0 即说明文件仍被占用，必须跳过清理。
     * <p>
     * 引用计数同时算上"仍在回收站、可恢复"的行（T55）：它们仍指着这份 COS 文件，误清会让恢复出来的
     * 图片成为死链。因此不能用 Wrapper 查询（全局逻辑删除会自动补 {@code isDelete = 0} 漏掉回收站行），
     * 统一走 {@link PictureMapper#countUrlReferences}。
     *
     * @param picture 待清理的图片记录
     */
    private void cleanupPictureFile(Picture picture) {
        String pictureUrl = picture.getUrl();
        long count = getBaseMapper().countUrlReferences(pictureUrl);
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
        // 7. 清除列表缓存，避免批量改分类/标签/名称后列表脏读。
        // 选图时已按 spaceId 过滤，所以改动只落在请求指定的这一个空间上
        this.clearPictureListCache(cacheScopeOf(spaceId));
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
    public VOutPaintingStatusVO getOutPaintingStatus() {
        VOutPaintingStatusVO status = new VOutPaintingStatusVO();
        status.setAvailable(outPaintingEnabled);
        status.setMessage(outPaintingEnabled ? null : OUT_PAINTING_MAINTENANCE_MESSAGE);
        return status;
    }

    /**
     * 维护门闩（T64）：必须在图片存在性、权限、幂等与配额**之前**判——关闭态下既不给调用方
     * "这张图存在与否"的探测信号，也不产生任何百炼调用、配额计数或幂等键。
     */
    private void checkOutPaintingAvailable() {
        if (!outPaintingEnabled) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, OUT_PAINTING_MAINTENANCE_MESSAGE);
        }
    }

    @Override
    public CreateOutPaintingTaskResponse createOutPaintingTask(CreatePictureOutPaintingTaskRequest createPictureOutPaintingTaskRequest, User loginUser) {
        checkOutPaintingAvailable();
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
        checkOutPaintingAvailable();
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
        // 一次打标可能跨多个 scope（选中的图来自不同空间，也可能含公共图库），逐个失效；
        // 去重后再自增，避免同一个 scope 的版本号被连加多次
        clearPictureListCache(pictureMap.values().stream()
                .map(picture -> cacheScopeOf(picture.getSpaceId()))
                .collect(Collectors.toSet()));
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
