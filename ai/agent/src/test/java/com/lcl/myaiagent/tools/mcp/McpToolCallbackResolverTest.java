package com.lcl.myaiagent.tools.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.ConnectException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19 单测（docs/plan.md T19）：搜图 MCP 不可达时，图库助手工具装配降级为空数组而不是整条对话失败，
 * 且失败后进入冷却、不在每次对话里重复白等一次连接超时（实测 20s ≈ request-timeout 默认值）。
 * <p>
 * 不依赖 Spring 上下文，也不连真 MCP 服务：Bean 查找与列工具两步都用 Supplier / 桩 provider 注入，
 * 时间用可控时钟（无 sleep，冷却到期可精确推进）。
 * </p>
 */
class McpToolCallbackResolverTest {

    private final FakeClock clock = new FakeClock();
    private final McpToolCallbackResolver resolver =
            new McpToolCallbackResolver(Duration.ofSeconds(60), clock);

    /** 可控时钟：推进冷却时间，避免测试里 sleep 真等 60s */
    private static final class FakeClock implements LongSupplier {

        private long nanos = 1_000_000_000L;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }

    @Test
    void defaultCooldownIsSixtySeconds() {
        assertThat(McpToolCallbackResolver.DEGRADE_COOLDOWN)
                .as("T19 拍板参数：冷却 60s")
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void returnsTheProviderToolsWhenMcpIsReachable() {
        AtomicInteger lookups = new AtomicInteger();

        ToolCallback[] tools = resolver.resolve(counting(lookups, () -> searchTools()));

        assertThat(namesOf(tools)).containsExactly("searchImage");
        assertThat(lookups).as("正常路径不改行为：照常解析一次").hasValue(1);
    }

    @Test
    void missingProviderBeanYieldsNoToolsAndDoesNotEnterCooldown() {
        AtomicInteger lookups = new AtomicInteger();

        assertThat(resolver.resolve(counting(lookups, () -> null))).isEmpty();
        assertThat(resolver.resolve(counting(lookups, () -> null))).isEmpty();

        assertThat(lookups)
                .as("Bean 不存在（AI_MCP_CLIENT_ENABLED=false 或没配连接）是配置选择、不是失败，不该进冷却")
                .hasValue(2);
    }

    @Test
    void beanLookupFailureIsDegradedInsteadOfFailingTheDialog() {
        ToolCallback[] tools = resolver.resolve(() -> {
            throw new IllegalStateException("MCP client bean 创建失败（桩）");
        });

        assertThat(tools)
                .as("取 Bean 那一步抛异常也要落到同一个降级路径上")
                .isEmpty();
    }

    @Test
    void listToolsFailureIsDegradedInsteadOfFailingTheDialog() {
        // 真机形态：服务没起时 getToolCallbacks() 抛 CompletionException 包着 ConnectException
        ToolCallbackProvider broken = () -> {
            throw new CompletionException(new ConnectException("Connection refused: localhost/127.0.0.1:8127"));
        };

        assertThat(resolver.resolve(() -> broken))
                .as("复现基线的失败形态：过去它让整条对话回 50000，现在只少一个工具")
                .isEmpty();
    }

    @Test
    void failureEntersCooldownSoTheNextDialogDoesNotWaitAgain() {
        AtomicInteger lookups = new AtomicInteger();
        ToolCallbackProvider broken = () -> {
            throw new CompletionException(new ConnectException("Connection refused"));
        };

        assertThat(resolver.resolve(counting(lookups, () -> broken))).isEmpty();
        assertThat(resolver.resolve(counting(lookups, () -> broken))).isEmpty();

        assertThat(lookups)
                .as("冷却期内不再尝试解析（否则每次对话都要白等一次 request-timeout）")
                .hasValue(1);
    }

    @Test
    void cooldownExpiresAndASuccessfulRetryClearsIt() {
        AtomicInteger lookups = new AtomicInteger();
        ToolCallbackProvider broken = () -> {
            throw new CompletionException(new ConnectException("Connection refused"));
        };
        assertThat(resolver.resolve(counting(lookups, () -> broken))).isEmpty();

        clock.advance(Duration.ofSeconds(60));
        assertThat(namesOf(resolver.resolve(counting(lookups, () -> searchTools()))))
                .as("冷却到期自动重试，服务起来了就自愈")
                .containsExactly("searchImage");

        assertThat(namesOf(resolver.resolve(counting(lookups, () -> searchTools()))))
                .as("成功后冷却被清除：下一次直接走解析")
                .containsExactly("searchImage");
        assertThat(lookups).hasValue(3);
    }

    @Test
    void concurrentDialogFallsBackImmediatelyWhileFirstProbeIsInFlight() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        CountDownLatch firstLookupStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstLookup = new CountDownLatch(1);
        Supplier<ToolCallbackProvider> blockingFailure = () -> {
            int attempt = lookups.incrementAndGet();
            if (attempt == 1) {
                firstLookupStarted.countDown();
                try {
                    releaseFirstLookup.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("首个 MCP 探测被中断（桩）", e);
                }
            }
            throw new IllegalStateException("MCP client 初始化失败（桩）");
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ToolCallback[]> first = executor.submit(() -> resolver.resolve(blockingFailure));
            assertThat(firstLookupStarted.await(5, TimeUnit.SECONDS))
                    .as("首个会话已经取得 MCP 探测资格并处于在途状态")
                    .isTrue();

            Future<ToolCallback[]> concurrent = executor.submit(() -> resolver.resolve(blockingFailure));
            assertThat(concurrent.get(5, TimeUnit.SECONDS))
                    .as("已有探测在途时，并发会话应立即按少一个工具降级")
                    .isEmpty();
            assertThat(lookups)
                    .as("同一时刻只允许一个会话调用 provider，不能排队重复支付连接超时")
                    .hasValue(1);

            releaseFirstLookup.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEmpty();
        } finally {
            releaseFirstLookup.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void nullToolArrayIsTreatedAsNoTools() {
        ToolCallbackProvider emptyProvider = () -> null;

        assertThat(resolver.resolve(() -> emptyProvider)).isEmpty();
    }

    private static ToolCallbackProvider searchTools() {
        return ToolCallbackProvider.from(ToolCallbacks.from(new StubMcpSearchTool()));
    }

    private static Supplier<ToolCallbackProvider> counting(AtomicInteger lookups,
                                                          Supplier<ToolCallbackProvider> delegate) {
        return () -> {
            lookups.incrementAndGet();
            return delegate.get();
        };
    }

    private static Set<String> namesOf(ToolCallback[] tools) {
        return Arrays.stream(tools)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }

    /** 假的 MCP 搜图工具（形状照 ai/image-search-mcp-server 的 searchImage） */
    static class StubMcpSearchTool {

        @Tool(description = "search image from web")
        public String searchImage(@ToolParam(description = "Search query keyword") String query) {
            return "https://img.example/a.jpg";
        }
    }
}
