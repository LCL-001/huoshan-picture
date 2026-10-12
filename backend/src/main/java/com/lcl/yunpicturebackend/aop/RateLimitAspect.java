package com.lcl.yunpicturebackend.aop;

import cn.hutool.core.util.StrUtil;
import com.lcl.yunpicturebackend.annotation.RateLimit;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.utils.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@link RateLimit} 的执行者（T49）：固定窗口计数，窗口内超过 {@code limit} 抛 42900。
 * <p>
 * 复用仓内既有范式（{@code UserServiceImpl.incrWithWindow}）：INCR + 首次设 TTL，并修复"丢了 TTL"的键。
 * 故意**不用 Lua**——单纯 INCR 语义下"INCR 成功但 expire 没执行"的最坏结果是窗口被延长，不是超发，
 * 与既有邮件/登录频控同口径；换成 Lua 收益有限却多一处脚本要维护。
 * </p>
 * <p>
 * <b>Redis 异常一律 fail-open</b>：限流是防护手段、不是业务正确性来源，Redis 抖动不该让整个站点 5xx
 * （与 {@code incrWithWindow} 的 catch→null 同口径）。
 * </p>
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    private static final String KEY_PREFIX = "huoshantuku:ratelimit:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 总开关：默认开；压测/联调用 --app.rate-limit.enabled=false 关闭 */
    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Around("@annotation(rateLimit)")
    public Object doRateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (!enabled) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            // 非 Web 上下文（如内部直调）：没有可限的调用方，直接放行
            return joinPoint.proceed();
        }
        String key = KEY_PREFIX + resolveKey(joinPoint, rateLimit) + ":" + resolvePrincipal(request, rateLimit.dimension());
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null) {
                if (count == 1L) {
                    stringRedisTemplate.expire(key, rateLimit.windowSeconds(), TimeUnit.SECONDS);
                } else {
                    // 键存在但没有 TTL（上次 expire 失败留下的）：补一个，避免永久计数
                    Long ttl = stringRedisTemplate.getExpire(key);
                    if (ttl != null && ttl == -1L) {
                        stringRedisTemplate.expire(key, rateLimit.windowSeconds(), TimeUnit.SECONDS);
                    }
                }
                if (count > rateLimit.limit()) {
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
                }
            }
        } catch (BusinessException e) {
            // 超限是正常拒绝路径，必须原样抛出，不能被下面的兜底吞掉
            throw e;
        } catch (Exception e) {
            log.warn("限流计数失败，放行本次请求, key={}, err={}", key, e.getMessage());
        }
        return joinPoint.proceed();
    }

    /** 限流键：注解显式指定优先，否则用"类简名.方法名" */
    private String resolveKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        if (StrUtil.isNotBlank(rateLimit.key())) {
            return rateLimit.key();
        }
        return joinPoint.getSignature().getDeclaringType().getSimpleName() + "." + joinPoint.getSignature().getName();
    }

    /**
     * 计数主体。USER 维度直接读 Spring Session 里的登录用户（不走 {@code getLoginUser}——那会多一次
     * getById 查库，限流不该给每个请求加一次 DB 读）；未登录再退回 IP。前缀区分维度，避免两类主体撞键。
     */
    private String resolvePrincipal(HttpServletRequest request, RateLimit.Dimension dimension) {
        if (RateLimit.Dimension.USER.equals(dimension)) {
            HttpSession session = request.getSession(false);
            Object userObj = session == null ? null : session.getAttribute(UserConstant.USER_LOGIN_STATE);
            if (userObj instanceof User && ((User) userObj).getId() != null) {
                return "u" + ((User) userObj).getId();
            }
        }
        return "i" + IpUtils.getClientIp(request);
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes) {
            return ((ServletRequestAttributes) Objects.requireNonNull(attributes)).getRequest();
        }
        return null;
    }
}
