package com.lcl.yunpicturebackend.manager.observability;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 图片列表缓存的命中率指标。
 * <p>
 * 本地与 Redis 两层分别暴露，命名沿用 Micrometer 的 cache.gets / cache.size 约定，
 * 接入 Prometheus 后可直接算命中率：
 * <pre>
 * rate(cache_gets_total{layer="caffeine",result="hit"})
 *   / rate(cache_gets_total{layer="caffeine"})
 * </pre>
 * 回源数据库的占比 = redis 层 miss 的比例（两级都未命中才会查库）。
 */
@Component
public class PictureListCacheMetrics {

    private static final String CACHE_NAME = "pictureList";
    private static final String LAYER_CAFFEINE = "caffeine";
    private static final String LAYER_REDIS = "redis";

    private final MeterRegistry meterRegistry;

    private final Cache<String, String> localCache;

    private final Counter redisHit;

    private final Counter redisMiss;

    public PictureListCacheMetrics(MeterRegistry meterRegistry,
                                   @Qualifier("pictureListLocalCache") Cache<String, String> localCache) {
        this.meterRegistry = meterRegistry;
        this.localCache = localCache;
        this.redisHit = redisCounter("hit");
        this.redisMiss = redisCounter("miss");
    }

    /**
     * 绑定本地缓存的命中/未命中计数与容量。
     * 依赖 CacheConfig 中开启的 recordStats()。
     */
    @PostConstruct
    public void bindLocalCache() {
        FunctionCounter.builder("cache.gets", localCache, cache -> cache.stats().hitCount())
                .tag("cache", CACHE_NAME).tag("layer", LAYER_CAFFEINE).tag("result", "hit")
                .register(meterRegistry);
        FunctionCounter.builder("cache.gets", localCache, cache -> cache.stats().missCount())
                .tag("cache", CACHE_NAME).tag("layer", LAYER_CAFFEINE).tag("result", "miss")
                .register(meterRegistry);
        Gauge.builder("cache.size", localCache, cache -> cache.estimatedSize())
                .tag("cache", CACHE_NAME).tag("layer", LAYER_CAFFEINE)
                .register(meterRegistry);
    }

    public void recordRedisHit() {
        redisHit.increment();
    }

    public void recordRedisMiss() {
        redisMiss.increment();
    }

    private Counter redisCounter(String result) {
        return Counter.builder("cache.gets")
                .tag("cache", CACHE_NAME).tag("layer", LAYER_REDIS).tag("result", result)
                .register(meterRegistry);
    }
}
