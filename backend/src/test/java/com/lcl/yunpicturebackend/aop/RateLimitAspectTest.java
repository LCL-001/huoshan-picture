package com.lcl.yunpicturebackend.aop;

import com.lcl.yunpicturebackend.annotation.RateLimit;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T49 限流切面的四条契约：窗口内放行且首次设 TTL、超限抛 42900、总开关关闭不碰 Redis、
 * Redis 异常 fail-open；外加计数主体（登录用户 vs IP 兜底）落在键里。纯 Mockito，无 Spring 上下文。
 */
@DisplayName("RateLimitAspect 固定窗口限流")
class RateLimitAspectTest {

    private static final Long USER_ID = 7L;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RateLimitAspect aspect;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        aspect = new RateLimitAspect();
        ReflectionTestUtils.setField(aspect, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(aspect, "enabled", true);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 取注解样张的载体：真注解实例（不是 mock），保证切面读到的就是声明值 */
    private static class Fixture {
        @RateLimit(key = "demo", limit = 3, windowSeconds = 60)
        void sample() {
        }
    }

    private RateLimit annotation() throws NoSuchMethodException {
        return Fixture.class.getDeclaredMethod("sample").getAnnotation(RateLimit.class);
    }

    private void stubOps() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    private void bindRequest(Long userId, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        MockHttpSession session = new MockHttpSession();
        if (userId != null) {
            User user = new User();
            user.setId(userId);
            session.setAttribute(UserConstant.USER_LOGIN_STATE, user);
        }
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ProceedingJoinPoint joinPoint() throws Throwable {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getDeclaringType()).thenReturn(Fixture.class);
        when(signature.getName()).thenReturn("sample");
        when(jp.getSignature()).thenReturn(signature);
        when(jp.proceed()).thenReturn("PROCEEDED");
        return jp;
    }

    @Test
    @DisplayName("窗口内未超额度：每次都放行，首次计数设置 TTL")
    void allowsWithinLimitAndSetsTtl() throws Throwable {
        bindRequest(USER_ID, "1.1.1.1");
        stubOps();
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L);
        when(redis.getExpire(anyString())).thenReturn(60L);
        ProceedingJoinPoint jp = joinPoint();

        for (int i = 0; i < 3; i++) {
            assertThat(aspect.doRateLimit(jp, annotation())).isEqualTo("PROCEEDED");
        }
        verify(redis).expire("huoshantuku:ratelimit:demo:u7", 60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("第 limit+1 次：抛 42900，且不再放行")
    void rejectsOverLimit() throws Throwable {
        bindRequest(USER_ID, "1.1.1.1");
        stubOps();
        when(ops.increment(anyString())).thenReturn(3L, 4L); // limit = 3
        when(redis.getExpire(anyString())).thenReturn(60L);
        ProceedingJoinPoint jp = joinPoint();

        assertThat(aspect.doRateLimit(jp, annotation())).isEqualTo("PROCEEDED");
        assertThatThrownBy(() -> aspect.doRateLimit(jp, annotation()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS.getCode());
        verify(jp).proceed(); // 恰好放行过一次
    }

    @Test
    @DisplayName("总开关关闭：不碰 Redis，直接放行")
    void disabledSkipsRedisEntirely() throws Throwable {
        ReflectionTestUtils.setField(aspect, "enabled", false);
        bindRequest(USER_ID, "1.1.1.1");
        ProceedingJoinPoint jp = joinPoint();

        assertThat(aspect.doRateLimit(jp, annotation())).isEqualTo("PROCEEDED");
        verifyNoInteractions(redis);
        verify(jp).proceed();
    }

    @Test
    @DisplayName("Redis 异常：fail-open，放行且不抛")
    void redisFailureFailsOpen() throws Throwable {
        bindRequest(USER_ID, "1.1.1.1");
        stubOps();
        when(ops.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        ProceedingJoinPoint jp = joinPoint();

        assertThat(aspect.doRateLimit(jp, annotation())).isEqualTo("PROCEEDED");
    }

    @Test
    @DisplayName("计数主体：登录用户 → u<id>；未登录 → IP 兜底 i<ip>")
    void principalIsUserThenIpFallback() throws Throwable {
        bindRequest(USER_ID, "9.9.9.9");
        stubOps();
        when(ops.increment(anyString())).thenReturn(1L);
        aspect.doRateLimit(joinPoint(), annotation());
        verify(redis).expire("huoshantuku:ratelimit:demo:u7", 60, TimeUnit.SECONDS);

        clearInvocations(redis);
        bindRequest(null, "9.9.9.9");
        when(ops.increment(anyString())).thenReturn(1L);
        aspect.doRateLimit(joinPoint(), annotation());
        verify(redis).expire("huoshantuku:ratelimit:demo:i9.9.9.9", 60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("无 Web 上下文：没有可限的调用方，放行")
    void noRequestContextProceeds() throws Throwable {
        ProceedingJoinPoint jp = joinPoint();
        assertThat(aspect.doRateLimit(jp, annotation())).isEqualTo("PROCEEDED");
        verify(redis, never()).opsForValue();
    }
}
