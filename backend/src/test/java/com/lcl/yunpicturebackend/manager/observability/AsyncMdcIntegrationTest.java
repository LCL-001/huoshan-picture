package com.lcl.yunpicturebackend.manager.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;

import javax.annotation.Resource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证 @Async 真的走了 AsyncConfig 配置的执行器，并且 MDC 被装饰器透传。
 * <p>
 * 单元测试只能证明 MdcTaskDecorator 自身的行为，证明不了它有没有被装配到 @Async 上；
 * 这里用真实 Spring 上下文里的一个探针 Bean 来验证装配链路。
 */
@SpringBootTest
class AsyncMdcIntegrationTest {

    @TestConfiguration
    static class ProbeConfiguration {

        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    /**
     * 探针：调用方设置 traceId 后，异步线程里应当能读到同一个值
     */
    static class AsyncProbe {

        @Async
        public CompletableFuture<String> currentTraceId() {
            return CompletableFuture.completedFuture(TraceContext.current());
        }
    }

    @Resource
    private AsyncProbe asyncProbe;

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void asyncMethodShouldInheritTraceIdFromCaller() throws Exception {
        TraceContext.set("trace-from-caller");
        try {
            assertEquals("trace-from-caller", asyncProbe.currentTraceId().get(5, TimeUnit.SECONDS));
        } finally {
            TraceContext.clear();
        }
    }

    @Test
    void asyncMethodShouldNotCarryTraceIdWhenCallerHasNone() throws Exception {
        TraceContext.clear();
        assertNull(asyncProbe.currentTraceId().get(5, TimeUnit.SECONDS));
    }
}
