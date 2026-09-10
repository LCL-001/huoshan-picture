package com.lcl.yunpicturebackend.manager.cache;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.lcl.yunpicturebackend.manager.observability.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 图片列表本地缓存失效器：本实例立即清空，并通过 Redis Pub/Sub 通知其它实例。
 * <p>
 * 解决的问题：本地 Caffeine 缓存没有版本号，{@code invalidateAll} 只作用于当前 JVM。
 * 多实例部署时 A 实例写入后，B 实例的本地缓存仍是旧数据，只能靠 5~10 分钟 TTL 自然过期。
 * <p>
 * 一致性口径：Pub/Sub 是"至多一次"投递，订阅方不在线就收不到，因此这里**不承诺强一致**，
 * 定位为"最终一致且有上界"——秒级广播失效为主，本地 TTL（5~10 分钟）为兜底。
 * Redis 层的 key 带版本号，天然跨实例生效，所以本类只负责本地层。
 */
@Slf4j
@Component
public class PictureListCacheInvalidator implements MessageListener {

    private final StringRedisTemplate stringRedisTemplate;

    private final ChannelTopic pictureListInvalidationTopic;

    private final Cache<String, String> pictureListLocalCache;

    /**
     * 本实例标识：pid@host 加随机后缀，用于区分同机多实例，并跳过自己发出的广播
     */
    private final String instanceId =
            ManagementFactory.getRuntimeMXBean().getName() + "#" + UUID.randomUUID().toString().substring(0, 8);

    public PictureListCacheInvalidator(StringRedisTemplate stringRedisTemplate,
                                       ChannelTopic pictureListInvalidationTopic,
                                       @Qualifier("pictureListLocalCache") Cache<String, String> pictureListLocalCache) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.pictureListInvalidationTopic = pictureListInvalidationTopic;
        this.pictureListLocalCache = pictureListLocalCache;
    }

    /**
     * 清空本实例本地缓存并广播给其它实例。
     * <p>
     * 调用方应先完成 Redis 版本号自增，再调用本方法：让其它实例清空本地缓存后
     * 回源读到的是新版本 key，不会把旧数据写回本地。
     */
    public void invalidateAll() {
        pictureListLocalCache.invalidateAll();
        CacheInvalidationEvent event =
                new CacheInvalidationEvent(instanceId, TraceContext.current(), System.currentTimeMillis());
        try {
            stringRedisTemplate.convertAndSend(pictureListInvalidationTopic.getTopic(), JSONUtil.toJsonStr(event));
            log.info("[cache-invalidate] 已广播图片列表缓存失效, topic={}, source={}",
                    pictureListInvalidationTopic.getTopic(), instanceId);
        } catch (Exception e) {
            // 广播失败不能影响业务写入：版本号已自增，其它实例的本地缓存由 TTL 兜底
            log.warn("[cache-invalidate] 广播失败，本实例已清空，其它实例将由 TTL 兜底, source={}", instanceId, e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        CacheInvalidationEvent event = parse(payload);
        if (event == null) {
            // 报文异常时按全量失效处理，宁可多清一次也不能留脏数据
            pictureListLocalCache.invalidateAll();
            log.warn("[cache-invalidate] 失效广播报文解析失败，已按全量失效处理, payload={}", payload);
            return;
        }
        if (instanceId.equals(event.getSource())) {
            // 自己发出的广播：本实例在 invalidateAll 里已经清过，跳过避免二次失效扩大缓存空窗
            return;
        }
        // 复用发布方的 traceId，让"收到广播"这条日志能和触发它的那次写入串起来。
        // 监听线程是复用的，先清掉上一条报文残留的 traceId，避免串错链路
        TraceContext.clear();
        TraceContext.set(event.getSourceTraceId());
        try {
            pictureListLocalCache.invalidateAll();
            log.info("[cache-invalidate] 收到图片列表缓存失效广播，已清空本地缓存, source={}, sourceTraceId={}, delay={}ms",
                    event.getSource(), TraceContext.currentOrDefault(), System.currentTimeMillis() - event.getTimestamp());
        } finally {
            TraceContext.clear();
        }
    }

    private CacheInvalidationEvent parse(String payload) {
        try {
            return JSONUtil.toBean(payload, CacheInvalidationEvent.class);
        } catch (Exception e) {
            return null;
        }
    }
}
