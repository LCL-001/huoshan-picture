package com.lcl.myaiagent.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ParallelSearchTool 单元测试 — 并发模型的四条硬契约：
 * 有界并发、单查询超时降级、部分失败继续、输入清洗。
 */
@DisplayName("ParallelSearchTool")
class ParallelSearchToolTest {

    private final WebSearchTool webSearchTool = mock(WebSearchTool.class);
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private ParallelSearchTool newTool(int poolSize, int timeoutSeconds) {
        executor = Executors.newFixedThreadPool(poolSize);
        return new ParallelSearchTool(webSearchTool, executor, timeoutSeconds);
    }

    @Test
    @DisplayName("并发上限生效：6 个查询、2 线程池，峰值并发恰为 2")
    void shouldBoundConcurrency() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(webSearchTool.searchWeb(anyString())).thenAnswer(invocation -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            Thread.sleep(150);
            active.decrementAndGet();
            return "结果";
        });

        ParallelSearchTool tool = newTool(2, 5);
        String result = tool.parallelSearch(List.of("q1", "q2", "q3", "q4", "q5", "q6"));

        assertThat(maxActive.get()).isEqualTo(2);
        assertThat(result).contains("## 查询: q6");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("单查询超时不拖垮整批：慢查询降级为占位，快查询照常返回")
    void shouldDegradeTimedOutQueryWithoutBlockingBatch() {
        when(webSearchTool.searchWeb(eq("slow"))).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return "不该出现";
        });
        when(webSearchTool.searchWeb(eq("fast"))).thenReturn("快速结果");

        ParallelSearchTool tool = newTool(2, 1);
        String result = tool.parallelSearch(List.of("slow", "fast"));

        assertThat(result)
                .contains("## 查询: slow")
                .contains("（该查询超时，已放弃）")
                .contains("## 查询: fast")
                .contains("快速结果")
                .doesNotContain("不该出现");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("部分失败返回部分结果：单查询抛错不影响其余查询")
    void shouldReturnPartialResultsOnFailure() {
        when(webSearchTool.searchWeb(eq("bad"))).thenThrow(new RuntimeException("API 限流"));
        when(webSearchTool.searchWeb(eq("good"))).thenReturn("正常结果");

        ParallelSearchTool tool = newTool(2, 5);
        String result = tool.parallelSearch(List.of("bad", "good"));

        assertThat(result)
                .contains("其中 1 个未成功")
                .contains("（该查询失败：API 限流）")
                .contains("正常结果");
    }

    @Test
    @DisplayName("输入清洗：去重、丢空白、截断到单批上限")
    void shouldCleanQueries() {
        when(webSearchTool.searchWeb(anyString())).thenReturn("结果");

        ParallelSearchTool tool = newTool(2, 5);
        String result = tool.parallelSearch(List.of(
                "q1", "q2", "q3", "q4", "q5", "  ", "q1", "q6", "q7", "q8"));

        // q1 重复折叠、空白丢弃、超过上限截断：实际执行 q1..q6 共 6 个
        assertThat(result).contains("共 6 个查询：");
        assertThat(result).doesNotContain("## 查询: q7").doesNotContain("## 查询: q8");
    }

    @Test
    @DisplayName("输入防御：null / 空列表 / 全空白 → 错误提示，不触发搜索")
    void shouldRejectInvalidInput() {
        ParallelSearchTool tool = newTool(2, 5);

        assertThat(tool.parallelSearch(null)).contains("Error: no queries provided.");
        assertThat(tool.parallelSearch(List.of())).contains("Error: no queries provided.");
        assertThat(tool.parallelSearch(List.of("  ", ""))).contains("Error: no valid queries provided.");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("任务真正并行执行：所有查询同时开始（用闭锁验证）")
    void shouldSubmitAllQueriesBeforeCollecting() {
        int queryCount = 3;
        CountDownLatch allStarted = new CountDownLatch(queryCount);
        when(webSearchTool.searchWeb(anyString())).thenAnswer(invocation -> {
            allStarted.countDown();
            // 等待所有查询都开跑后再返回：若串行执行，第一个查询就会在这里等到超时
            assertThat(allStarted.await(2, TimeUnit.SECONDS)).isTrue();
            return "结果";
        });

        ParallelSearchTool tool = newTool(queryCount, 5);
        String result = tool.parallelSearch(List.of("q1", "q2", "q3"));

        assertThat(result).doesNotContain("超时");
    }
}
