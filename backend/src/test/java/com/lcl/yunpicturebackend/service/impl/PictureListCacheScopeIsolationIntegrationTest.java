package com.lcl.yunpicturebackend.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureQueryRequest;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureReviewRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.enums.PictureReviewStatusEnum;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.IUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 图片列表缓存"失效按空间切分"（docs/plan.md T34）的隔离性契约。
 * <p>
 * 改动前：任何一次图片写入都 INCR 同一个全局版本号，所有缓存的列表（含热门公共图库与无关空间）
 * 一起作废——写 A 空间的一张图，会让公共图库的列表回源重建。
 * 改动后：只 INCR 这次写入影响的 scope（空间图库是该空间、公共图库是 public），
 * 无关 scope 的 Redis 条目保持可达。
 * <p>
 * 断言用 {@code @SpyBean} 数 {@code loadFromDb} 的调用次数（与
 * {@code CacheRebuildLockIntegrationTest} 同一测试缝），而不是断言 Redis key 的字面形状：
 * 这样测的是"无关空间的列表还会不会回源查库"这个行为，而不是实现细节。
 * 本地 Caffeine 层仍按既定取舍全清，所以这里看的是 L2 是否还命中。
 * <p>
 * 注意：本类**不能** mock {@code PictureListCacheInvalidator}（否则本地层不会被清，
 * 两次调用都会命中 L1，回源次数永远是 1，用例失去意义）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PictureListCacheScopeIsolationIntegrationTest {

    /**
     * 版本号 key 前缀。改动前是全局单 key（不带 scope 后缀），改动后每个 scope 一个。
     */
    private static final String VERSION_KEY_PREFIX = "huoshantuku:listPictureVOByPage:version";

    /**
     * 两个不会与本机真实空间冲突的空间 id：本用例只按空间 id 分组缓存，不依赖 Space 表有对应行
     * （{@code doPictureReview} 不校验空间是否存在）。
     */
    private static final long SPACE_A = 909090001L;
    private static final long SPACE_B = 909090002L;

    @SpyBean
    private PictureServiceImpl pictureService;

    @Autowired
    private IPictureService pictureServiceForQuery;

    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private User admin;

    @BeforeAll
    void setUpAdmin() {
        admin = new User();
        admin.setUserAccount("t34-scope-" + System.currentTimeMillis());
        admin.setUserPassword(userService.getEncryptPassword("password123"));
        admin.setUserName("t34-scope-admin");
        userService.save(admin);
    }

    @AfterAll
    void cleanFixtures() {
        pictureServiceForQuery.remove(Wrappers.<Picture>lambdaQuery().eq(Picture::getUserId, admin.getId()));
        if (admin != null) {
            userService.removeById(admin.getId());
        }
        // 版本号 key 是本用例自己 seed 的，删掉避免污染后续运行与其它用例
        stringRedisTemplate.delete(publicVersionKey());
        stringRedisTemplate.delete(VERSION_KEY_PREFIX + ":" + SPACE_A);
        stringRedisTemplate.delete(VERSION_KEY_PREFIX + ":" + SPACE_B);
    }

    @BeforeEach
    void cleanPictures() {
        pictureServiceForQuery.remove(Wrappers.<Picture>lambdaQuery().eq(Picture::getUserId, admin.getId()));
    }

    /**
     * 验收核心：写一个空间不得作废公共图库的列表缓存。
     * <p>
     * 改动前这条会红：全局版本号自增后，公共图库那条 Redis 条目变成不可达，
     * 第二次同查询只能回源查库（loadFromDb 被调用 2 次）。
     */
    @Test
    void spaceWriteShouldNotInvalidatePublicListCache() {
        PictureQueryRequest publicQuery = publicQuery();

        // 冷启动：公共图库这条查询第一次回源，结果写进 L2
        pictureService.listPictureVOByPageByCache(publicQuery, null);

        // 写 A 空间的一张图
        reviewPictureInSpace(SPACE_A);

        // 同一条公共查询再来：L1 已被全清（本地层的既定取舍），只应靠 L2 命中，不得回源
        pictureService.listPictureVOByPageByCache(publicQuery, null);

        verify(pictureService, times(1)).loadFromDb(any(), anyLong(), anyLong(), any());
    }

    /**
     * 反向护栏：写公共图库自己的图，公共图库的缓存必须失效。
     * <p>
     * 防止"按空间切分"被实现成"干脆什么都不失效"——那种实现能让上一个用例变绿，
     * 但会让公共图库永远读到陈旧列表。
     */
    @Test
    void publicWriteShouldStillInvalidatePublicListCache() {
        PictureQueryRequest publicQuery = publicQuery();

        pictureService.listPictureVOByPageByCache(publicQuery, null);

        // 写公共图库的一张图（spaceId 为 null 即公共图库）
        reviewPictureInSpace(null);

        pictureService.listPictureVOByPageByCache(publicQuery, null);

        verify(pictureService, times(2)).loadFromDb(any(), anyLong(), anyLong(), any());
    }

    /**
     * 精确性：写 A 空间只能动 A 空间自己的版本号，公共图库与无关空间 B 都不动。
     * <p>
     * 三个 scope 的版本号先 seed 成同一个值，再观察谁变了——改动前这条会红，
     * 因为自增的是全局单 key，带 scope 后缀的 key 纹丝不动。
     */
    @Test
    void spaceWriteShouldBumpOnlyItsOwnScopeVersion() {
        stringRedisTemplate.opsForValue().set(publicVersionKey(), "100");
        stringRedisTemplate.opsForValue().set(VERSION_KEY_PREFIX + ":" + SPACE_A, "100");
        stringRedisTemplate.opsForValue().set(VERSION_KEY_PREFIX + ":" + SPACE_B, "100");

        reviewPictureInSpace(SPACE_A);

        assertEquals("101", stringRedisTemplate.opsForValue().get(VERSION_KEY_PREFIX + ":" + SPACE_A),
                "写入所在 scope 的版本号必须自增，否则该空间的列表不会刷新");
        assertEquals("100", stringRedisTemplate.opsForValue().get(publicVersionKey()),
                "写空间不得影响公共图库的版本号");
        assertEquals("100", stringRedisTemplate.opsForValue().get(VERSION_KEY_PREFIX + ":" + SPACE_B),
                "写 A 空间不得影响无关空间 B 的版本号");
    }

    /**
     * 唯一的 searchText 保证是冷 key，不与其它用例或正在运行的实例互相干扰；
     * spaceId 为空时不会走空间权限校验，request 可以传 null（与 CacheRebuildLockIntegrationTest 同）。
     */
    private PictureQueryRequest publicQuery() {
        PictureQueryRequest query = new PictureQueryRequest();
        query.setCurrent(1);
        query.setPageSize(3);
        query.setSearchText("t34-scope-" + UUID.randomUUID());
        return query;
    }

    /**
     * 在指定空间造一张待审核的图并审核通过，触发一次列表缓存失效。
     *
     * @param spaceId 目标空间，null 表示公共图库
     */
    private void reviewPictureInSpace(Long spaceId) {
        Picture picture = newPicture(spaceId);
        pictureServiceForQuery.save(picture);
        PictureReviewRequest reviewRequest = new PictureReviewRequest();
        reviewRequest.setId(picture.getId());
        reviewRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        pictureService.doPictureReview(reviewRequest, admin);
    }

    private Picture newPicture(Long spaceId) {
        Picture picture = new Picture();
        String key = UUID.randomUUID().toString().replace("-", "");
        picture.setUrl("https://cos.example.com/t34/" + key + ".jpg");
        picture.setThumbnailUrl("https://cos.example.com/t34/" + key + "_thumb.jpg");
        picture.setName("t34-scope-" + key);
        picture.setPicSize(1024L);
        picture.setPicWidth(32);
        picture.setPicHeight(32);
        picture.setPicScale(1.0D);
        picture.setPicFormat("jpg");
        picture.setUserId(admin.getId());
        picture.setSpaceId(spaceId);
        picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        return picture;
    }

    private String publicVersionKey() {
        return VERSION_KEY_PREFIX + ":public";
    }
}
