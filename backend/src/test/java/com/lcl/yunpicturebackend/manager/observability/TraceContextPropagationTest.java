package com.lcl.yunpicturebackend.manager.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * traceId 跨线程传递与线程复用不串号的单元测试。
 * <p>
 * 这两点靠运行时日志很难稳定复现，用测试锁住行为更可靠。
 */
class TraceContextPropagationTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void resolveShouldReuseSafeIncomingTraceId() {
        assertEquals("abc-123_XYZ", TraceContext.resolve("abc-123_XYZ"));
    }

    @Test
    void resolveShouldRejectUnsafeInputToPreventLogInjection() {
        String injected = "abc\n[ERROR] 伪造的日志行";
        String resolved = TraceContext.resolve(injected);
        assertNotEquals(injected, resolved);
        assertTrue(resolved.matches("^[A-Za-z0-9_-]{1,64}$"));
    }

    @Test
    void mdcThreadPoolExecutorShouldPropagateTraceIdToWorkerThread() throws Exception {
        MdcThreadPoolExecutor executor = newExecutor("test-mdc-propagate");
        try {
            AtomicReference<String> workerTraceId = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            TraceContext.set("trace-from-request");
            executor.execute(() -> {
                workerTraceId.set(TraceContext.current());
                latch.countDown();
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS), "任务未在预期时间内执行");
            assertEquals("trace-from-request", workerTraceId.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void workerThreadShouldNotLeakTraceIdIntoNextTask() throws Exception {
        MdcThreadPoolExecutor executor = newExecutor("test-mdc-leak");
        try {
            CountDownLatch firstDone = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            AtomicReference<String> secondTaskTraceId = new AtomicReference<>("unset");

            TraceContext.set("trace-A");
            executor.execute(firstDone::countDown);
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));

            // 提交第二个任务时当前线程已无 traceId，同一个工作线程不应残留上一个任务的值
            TraceContext.clear();
            executor.execute(() -> {
                secondTaskTraceId.set(TraceContext.current());
                secondDone.countDown();
            });
            assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            assertNull(secondTaskTraceId.get(), "线程复用时 traceId 发生了串号");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mdcTaskDecoratorShouldPropagateTraceIdToOtherThread() throws Exception {
        TraceContext.set("trace-decorator");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable decorated = new MdcTaskDecorator().decorate(() -> seen.set(TraceContext.current()));

        Thread thread = new Thread(decorated, "test-decorator");
        thread.start();
        thread.join(5000);
        assertEquals("trace-decorator", seen.get());
    }

    private MdcThreadPoolExecutor newExecutor(String threadName) {
        return new MdcThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                runnable -> new Thread(runnable, threadName),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
