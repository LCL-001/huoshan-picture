package com.lcl.yunpicturebackend.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 固定窗口限流（T49）：标在需要限流的控制器方法上，由 {@code RateLimitAspect} 执行。
 * <p>
 * 只用于**会调外部付费服务或可被用户滥用**的高成本/敏感端点（见 spec「限流不做：全站 blanket 限流」）。
 * 计数走 Redis（键 {@code huoshantuku:ratelimit:<key>:<principal>}），窗口内超过 {@link #limit()} 抛
 * {@code ErrorCode.TOO_MANY_REQUESTS}。总开关 {@code app.rate-limit.enabled} 默认开。
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流键的稳定标识（如 {@code ai:chat}）。留空则退化为"类简名.方法名"。
     * 显式给键是为了让同一逻辑端点改名/挪类后限流额度不被重置。
     */
    String key() default "";

    /** 窗口内允许的最大请求数 */
    int limit();

    /** 窗口长度（秒） */
    int windowSeconds();

    /** 计数维度：默认按登录用户；未登录请求按客户端 IP 兜底 */
    Dimension dimension() default Dimension.USER;

    enum Dimension {
        /** 登录用户 id（未登录时退回 IP） */
        USER,
        /** 客户端 IP */
        IP
    }
}
