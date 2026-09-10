package com.lcl.yunpicturebackend.manager.observability;

import cn.hutool.core.util.StrUtil;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全链路追踪上下文：traceId 的生成、校验与 MDC 读写。
 * <p>
 * 所有需要透传链路标识的地方统一走这里，避免各处硬编码 MDC key。
 */
public final class TraceContext {

    /**
     * MDC 与 WebSocket session attribute 共用的 key
     */
    public static final String TRACE_ID = "traceId";

    /**
     * 与前端/网关约定的请求头、响应头名称
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 只接受安全字符集，防止外部传入的内容污染日志（日志注入）
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private TraceContext() {
    }

    /**
     * 优先复用调用方传入的 traceId，缺失或格式非法时生成新的
     */
    public static String resolve(String incoming) {
        if (StrUtil.isNotBlank(incoming) && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 当前线程的 traceId，可能为 null
     */
    public static String current() {
        return MDC.get(TRACE_ID);
    }

    /**
     * 当前线程的 traceId，缺失时返回占位符，便于直接写日志或请求头
     */
    public static String currentOrDefault() {
        return StrUtil.blankToDefault(current(), "-");
    }

    public static void set(String traceId) {
        if (StrUtil.isNotBlank(traceId)) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    /**
     * 清理当前线程的 traceId。
     * 只删自己写入的 key，不用 MDC.clear()，避免影响其他组件放进 MDC 的内容。
     */
    public static void clear() {
        MDC.remove(TRACE_ID);
    }

    /**
     * 快照当前线程的 MDC，供跨线程传递
     */
    public static Map<String, String> snapshot() {
        return MDC.getCopyOfContextMap();
    }

    /**
     * 恢复 MDC 快照；快照为 null 时清空，用于还原工作线程进入任务前的状态
     */
    public static void restore(Map<String, String> snapshot) {
        if (snapshot == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
    }
}
