package com.lcl.yunpicturebackend.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 图片列表本地缓存配置。
 * <p>
 * 抽成 Bean 而不是留在 Service 内部，是为了让指标埋点（命中率）和后续的跨实例失效广播
 * 能复用同一个实例，不用改两遍。
 */
@Configuration
public class CacheConfig {

    @Bean("pictureListLocalCache")
    public Cache<String, String> pictureListLocalCache() {
        return Caffeine.newBuilder().initialCapacity(1024)
                .maximumSize(10000L)
                // 开启统计：命中率指标依赖它，不开的话 hit/miss 恒为 0
                .recordStats()
                // 每条缓存独立随机 TTL（5~10 分钟），打散过期时间降低雪崩风险
                .expireAfter(new Expiry<String, String>() {
                    private long randomTtlNanos() {
                        return TimeUnit.MINUTES.toNanos(5 + ThreadLocalRandom.current().nextInt(5));
                    }

                    @Override
                    public long expireAfterCreate(String key, String value, long currentTime) {
                        return randomTtlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, String value, long currentTime, long currentDuration) {
                        return randomTtlNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, String value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }
}
