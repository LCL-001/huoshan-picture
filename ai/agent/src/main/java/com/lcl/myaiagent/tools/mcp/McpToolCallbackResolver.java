package com.lcl.myaiagent.tools.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 搜图 MCP 工具回调的解析与降级（T19）：MCP 服务（ai/image-search-mcp-server，8127）不可达时，
 * 图库助手**少一个工具照常开对话**，而不是整条对话失败。
 * <p>
 * 背景：{@code ToolCallbackProvider#getToolCallbacks()} 会触发 MCP 客户端首次连接（{@code initialized: false}），
 * 服务没起时它抛异常——实测 HTTP 200 但体是 {@code {"code":50000}}、耗时 ≈ {@code spring.ai.mcp.client.request-timeout}
 * 的默认值 20s、连 SSE 流都没有。失败即降级为空数组，沿用 T9 的既有口径（"少一个工具照常开对话"）。
 * </p>
 * <p>
 * 降级后进入 {@link #DEGRADE_COOLDOWN} 冷却：服务可能长时间不可用，而每次失败都要付一次连接超时，
 * 冷却期内直接按"本次不挂"处理、不再尝试；同一时刻也只允许一个会话执行探测，其余并发会话立即降级；
 * 到期自动重试一次，成功即清除冷却（失败被确认后 ≤1 个冷却周期再次探测）。
 * 冷却只针对"解析失败"，{@code Bean} 不存在（{@code AI_MCP_CLIENT_ENABLED=false} 等）不算失败：那是配置选择，不进冷却。
 * </p>
 */
@Slf4j
@Component
public class McpToolCallbackResolver {

    /** 解析失败后的冷却时长：失败被确认后，最多再等这段时间发起下一次探测 */
    static final Duration DEGRADE_COOLDOWN = Duration.ofSeconds(60);

    private static final ToolCallback[] NO_TOOLS = new ToolCallback[0];

    /** 无冷却的哨兵值：{@code nanoTime()} 可能为负，0 不能当"没冷却"用 */
    private static final long NO_COOLDOWN = Long.MIN_VALUE;

    private final Duration cooldown;
    private final LongSupplier nanoClock;
    private final AtomicLong nextAttemptNanos = new AtomicLong(NO_COOLDOWN);

    /** single-flight 门闩：MCP 探测在途时，其余会话立即按无搜图工具降级，不排队等待 */
    private final Lock probeLock = new ReentrantLock();

    public McpToolCallbackResolver() {
        this(DEGRADE_COOLDOWN, System::nanoTime);
    }

    McpToolCallbackResolver(Duration cooldown, LongSupplier nanoClock) {
        this.cooldown = cooldown;
        this.nanoClock = nanoClock;
    }

    /**
     * 解析搜图 MCP 工具回调，失败一律降级为空数组（本方法不抛异常）。
     *
     * @param providerSupplier 取 MCP 工具回调 Bean（不存在时返回 null）；用 Supplier 而不是直接传 Bean，
     *                         是为了让"取 Bean"与"列工具"两步的失败都能落到同一个降级路径上
     */
    public ToolCallback[] resolve(Supplier<ToolCallbackProvider> providerSupplier) {
        if (isCoolingDown()) {
            log.info("搜图 MCP 工具处于降级冷却期，本次会话不挂载搜图工具（到期自动重试）");
            return NO_TOOLS;
        }
        if (!probeLock.tryLock()) {
            log.info("搜图 MCP 工具正在由其他会话探测，本次会话立即降级、不挂载搜图工具");
            return NO_TOOLS;
        }
        try {
            // 防止前一个探测刚失败并释放锁：取得资格后必须再看一次它写入的冷却截止时间
            if (isCoolingDown()) {
                log.info("搜图 MCP 工具处于降级冷却期，本次会话不挂载搜图工具（到期自动重试）");
                return NO_TOOLS;
            }
            ToolCallbackProvider provider = providerSupplier.get();
            if (provider == null) {
                // T9 口径：MCP client 没开或没配连接时这个 Bean 不存在，少一个工具照常开对话，不算失败
                return NO_TOOLS;
            }
            ToolCallback[] callbacks = provider.getToolCallbacks();
            nextAttemptNanos.set(NO_COOLDOWN);
            return callbacks == null ? NO_TOOLS : callbacks;
        } catch (Exception e) {
            nextAttemptNanos.set(nanoClock.getAsLong() + cooldown.toNanos());
            // 异常只来自 MCP 客户端自身的连接/协议错误，最多带上我们自己配的服务地址，不含用户凭据，可以进日志
            log.warn("搜图 MCP 工具不可用（{}: {}），本次会话不挂载搜图工具，对话照常进行；{}s 内不再重试",
                    e.getClass().getSimpleName(), e.getMessage(), cooldown.toSeconds());
            return NO_TOOLS;
        } finally {
            probeLock.unlock();
        }
    }

    private boolean isCoolingDown() {
        return nanoClock.getAsLong() < nextAttemptNanos.get();
    }
}
