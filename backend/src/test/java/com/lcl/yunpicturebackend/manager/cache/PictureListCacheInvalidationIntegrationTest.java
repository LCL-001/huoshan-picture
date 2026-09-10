package com.lcl.yunpicturebackend.manager.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片列表缓存跨实例失效的集成验证。
 * <p>
 * 不 mock Redis：真实走一遍 pub/sub。单测只能证明"发了一条消息"，
 * 证明不了订阅容器有没有被 Spring 装配起来、对端实例的本地缓存会不会真的被清掉。
 */
@SpringBootTest
class PictureListCacheInvalidationIntegrationTest {

    /**
     * 对端实例收到广播的最长等待时间，pub/sub 是异步投递，留足余量避免偶发失败
     */
    private static final long AWAIT_TIMEOUT_MS = 5000;

    @Resource(name = "pictureListLocalCache")
    private Cache<String, String> pictureListLocalCache;

    @Resource
    private PictureListCacheInvalidator pictureListCacheInvalidator;

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Resource
    private ChannelTopic pictureListInvalidationTopic;

    @Resource
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * 应用启动时装配的监听容器必须真的订阅了失效频道
     */
    @Resource(name = "redisMessageListenerContainer")
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @Test
    void broadcastShouldReachAppContainerAndInvalidatePeerLocalCache() throws Exception {
        // 应用自带的监听容器确实订阅了这个频道（装配链路验证）
        CountDownLatch appContainerReceived = new CountDownLatch(1);
        MessageListener probe = (message, pattern) -> appContainerReceived.countDown();
        redisMessageListenerContainer.addMessageListener(probe, pictureListInvalidationTopic);

        // 模拟另一个实例：独立的 Caffeine 实例 + 独立订阅者，共用同一个 Redis
        Cache<String, String> peerLocalCache = Caffeine.newBuilder().build();
        PictureListCacheInvalidator peerInvalidator =
                new PictureListCacheInvalidator(stringRedisTemplate, pictureListInvalidationTopic, peerLocalCache);
        RedisMessageListenerContainer peerContainer = new RedisMessageListenerContainer();
        peerContainer.setConnectionFactory(redisConnectionFactory);
        peerContainer.addMessageListener(peerInvalidator, pictureListInvalidationTopic);
        peerContainer.afterPropertiesSet();
        peerContainer.start();

        try {
            pictureListLocalCache.put("probe-key", "local-value");
            peerLocalCache.put("probe-key", "peer-value");

            // 等价于 doClearPictureListCache 里的调用：本实例清空 + 广播
            pictureListCacheInvalidator.invalidateAll();

            // 本实例同步清空，调用方立刻可见
            assertNull(pictureListLocalCache.getIfPresent("probe-key"), "发布方本地缓存应立即失效");
            // 对端实例在超时时间内收到广播并清空本地缓存
            awaitUntil(() -> peerLocalCache.getIfPresent("probe-key") == null);
            assertNull(peerLocalCache.getIfPresent("probe-key"), "对端本地缓存应在广播后失效");
            // 订阅容器的装配链路确实通
            assertTrue(appContainerReceived.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "应用装配的 RedisMessageListenerContainer 应收到广播");
        } finally {
            peerContainer.stop();
            peerContainer.destroy();
            redisMessageListenerContainer.removeMessageListener(probe);
        }
    }

    /**
     * 报文异常时宁可多清一次，也不能因为解析失败留下脏缓存
     */
    @Test
    void malformedBroadcastShouldFallbackToFullInvalidation() {
        pictureListLocalCache.put("probe-key", "local-value");

        byte[] channel = pictureListInvalidationTopic.getTopic().getBytes(StandardCharsets.UTF_8);
        byte[] body = "not-a-json-event".getBytes(StandardCharsets.UTF_8);
        pictureListCacheInvalidator.onMessage(new DefaultMessage(channel, body), null);

        assertNull(pictureListLocalCache.getIfPresent("probe-key"), "报文解析失败时应按全量失效处理");
    }

    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
