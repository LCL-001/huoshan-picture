package com.lcl.yunpicturebackend.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片列表缓存 scope 推导的纯单元断言（docs/plan.md T34）。
 * <p>
 * 这些不变量是"失效按空间切分"的前提：读路径与写路径共用同一套推导，且不同 scope 的
 * key 空间必须不相交——一旦撞了，写一个空间就会误伤另一个空间的缓存。
 * 端到端行为在 {@code PictureListCacheScopeIsolationIntegrationTest}（需 MySQL/Redis，不进 CI）。
 */
class PictureListCacheScopeTest {

    @Test
    void nullSpaceIdShouldMapToPublicScope() {
        assertEquals(PictureServiceImpl.CACHE_SCOPE_PUBLIC, PictureServiceImpl.cacheScopeOf(null),
                "spaceId 为空表示公共图库，必须是独立的 public scope");
    }

    @Test
    void spaceIdShouldMapToItsOwnScope() {
        assertEquals("7", PictureServiceImpl.cacheScopeOf(7L));
        assertNotEquals(PictureServiceImpl.cacheScopeOf(7L), PictureServiceImpl.cacheScopeOf(8L),
                "不同空间必须是不同 scope，否则写一个空间会误伤另一个");
    }

    @Test
    void publicScopeShouldNotCollideWithAnyNumericSpaceId() {
        // scope 用字符串而不是数字，就是为了让 public 不可能与任何空间 id 相撞
        assertNotEquals(PictureServiceImpl.cacheScopeOf(null), PictureServiceImpl.cacheScopeOf(0L));
        assertNotEquals(PictureServiceImpl.cacheScopeOf(null), PictureServiceImpl.cacheScopeOf(1L));
    }

    @Test
    void versionKeysShouldDifferPerScopeAndSharePrefix() {
        String publicKey = PictureServiceImpl.cacheVersionKey(PictureServiceImpl.CACHE_SCOPE_PUBLIC);
        String spaceKey = PictureServiceImpl.cacheVersionKey(PictureServiceImpl.cacheScopeOf(7L));

        assertNotEquals(publicKey, spaceKey, "不同 scope 的版本号 key 必须不同");
        assertTrue(publicKey.startsWith("huoshantuku:listPictureVOByPage:version"),
                "版本号 key 应保留既有前缀，便于运维按前缀排查");
        assertTrue(publicKey.endsWith(":" + PictureServiceImpl.CACHE_SCOPE_PUBLIC));
    }
}
