package com.lcl.yunpicturebackend.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureQueryRequest;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 缓存重建分布式锁的健壮性验证：把锁 TTL 调成 1 秒，用注入的慢查询复现两种时序。
 * <p>
 * 只测锁本身（"有没有重复回源"），不测业务结果——两个用例都用唯一的 searchText 制造冷 key，
 * 避免与其它用例、正在运行的实例互相干扰。
 */
@SpringBootTest(properties = {
        "app.cache.rebuild-lock-ttl-seconds=1",
        "app.cache.rebuild-warn-ms=100"
})
class CacheRebuildLockIntegrationTest {

    @SpyBean
    private PictureServiceImpl pictureService;

    /**
     * 重建耗时（600ms）小于锁 TTL（1s）：第二个请求抢不到锁，走有限重试后明确失败，
     * 回源只发生一次。这是 TTL 留足余量时的正确行为。
     */
    @Test
    void slowRebuildWithinLockTtlShouldNotDuplicateQuery() throws Exception {
        PictureQueryRequest query = newQuery();
        CountDownLatch rebuildStarted = new CountDownLatch(1);
        stubSlowRebuild(rebuildStarted, 600);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Page<PictureVO>> first = pool.submit(() -> pictureService.listPictureVOByPageByCache(query, null));
            assertTrue(rebuildStarted.await(5, TimeUnit.SECONDS), "首个请求应已进入回源");
            Future<Page<PictureVO>> second = pool.submit(() -> pictureService.listPictureVOByPageByCache(query, null));

            assertNotNull(first.get(10, TimeUnit.SECONDS));
            ExecutionException secondFailure =
                    assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, secondFailure.getCause());
            assertTrue(secondFailure.getCause().getMessage().contains("系统繁忙"),
                    "抢锁失败且重试耗尽时应给出明确业务提示，而不是返回空页");

            verify(pictureService, times(1)).loadFromDb(any(), anyLong(), anyLong(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 重建耗时（4s）大于锁 TTL（1s）：等锁过期后再来一个请求，会重复回源。
     * <p>
     * 这个用例把"TTL 必须大于重建耗时"这条约束固化成可执行的证据——
     * 一旦有人把 TTL 调到小于重建耗时，重复回源就会真实发生。
     */
    @Test
    void rebuildOutlastingLockTtlAllowsDuplicateQuery() throws Exception {
        PictureQueryRequest query = newQuery();
        CountDownLatch rebuildStarted = new CountDownLatch(1);
        stubSlowRebuild(rebuildStarted, 4000);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Page<PictureVO>> first = pool.submit(() -> pictureService.listPictureVOByPageByCache(query, null));
            assertTrue(rebuildStarted.await(5, TimeUnit.SECONDS), "首个请求应已进入回源");
            // 等锁过期（TTL=1s）后再发起，此时第一次重建仍在进行中
            Thread.sleep(1600);
            Future<Page<PictureVO>> second = pool.submit(() -> pictureService.listPictureVOByPageByCache(query, null));

            assertNotNull(first.get(20, TimeUnit.SECONDS));
            assertNotNull(second.get(20, TimeUnit.SECONDS));
            // 锁提前释放 => 第二个请求也抢到锁并重复查库
            verify(pictureService, times(2)).loadFromDb(any(), anyLong(), anyLong(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 唯一的 searchText 保证是冷 key；spaceId 为空时不会走空间权限校验，request 可传 null
     */
    private PictureQueryRequest newQuery() {
        PictureQueryRequest query = new PictureQueryRequest();
        query.setCurrent(1);
        query.setPageSize(3);
        query.setSearchText("p1-2-" + UUID.randomUUID());
        return query;
    }

    private void stubSlowRebuild(CountDownLatch rebuildStarted, long slowMillis) throws Exception {
        doAnswer(invocation -> {
            rebuildStarted.countDown();
            Thread.sleep(slowMillis);
            return invocation.callRealMethod();
        }).when(pictureService).loadFromDb(any(), anyLong(), anyLong(), any());
    }
}
