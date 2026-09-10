package com.lcl.yunpicturebackend.manager.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片列表缓存失效广播的报文。
 * <p>
 * 只承载"谁发的、对应哪条链路"这类排查信息，不承载缓存内容：
 * 失效语义是幂等的全量清空，收到即清，不依赖报文里的任何业务字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheInvalidationEvent {

    /**
     * 发布方实例标识，订阅方据此跳过自己发出的广播
     */
    private String source;

    /**
     * 触发失效的写入请求 traceId，用于把订阅方的日志和那次写入串起来
     */
    private String sourceTraceId;

    /**
     * 发布时间戳（毫秒），排查广播延迟时用
     */
    private long timestamp;
}
