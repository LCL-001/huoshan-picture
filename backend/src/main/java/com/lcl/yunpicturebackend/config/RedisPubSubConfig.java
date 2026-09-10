package com.lcl.yunpicturebackend.config;

import com.lcl.yunpicturebackend.manager.cache.PictureListCacheInvalidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 缓存失效广播的 Redis Pub/Sub 配置。
 * <p>
 * Spring Boot 2.7 的 RedisAutoConfiguration 只提供 RedisTemplate/StringRedisTemplate，
 * 不会创建监听容器（容器需要有订阅方才有意义），所以这里显式声明一个，
 * 并把本地缓存失效订阅者挂到图片列表失效频道上。
 */
@Configuration
public class RedisPubSubConfig {

    /**
     * 图片列表本地缓存失效广播频道，Redis 里全局唯一，各实例订阅同一频道
     */
    public static final String PICTURE_LIST_INVALIDATION_CHANNEL = "huoshantuku:channel:pictureList:invalidate";

    @Bean
    public ChannelTopic pictureListInvalidationTopic() {
        return new ChannelTopic(PICTURE_LIST_INVALIDATION_CHANNEL);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            PictureListCacheInvalidator pictureListCacheInvalidator,
            ChannelTopic pictureListInvalidationTopic) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 容器是 SmartLifecycle，Spring 启动时自动订阅，关闭时自动退订
        container.addMessageListener(pictureListCacheInvalidator, pictureListInvalidationTopic);
        return container;
    }
}
