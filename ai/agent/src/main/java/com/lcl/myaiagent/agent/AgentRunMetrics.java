package com.lcl.myaiagent.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 运行期风险的可观测计数（R1 处置："接受上游连接不随取消关闭" + 加埋点）。
 * <p>
 * 背景（2026-09-14 review + T18/T8-c 实测）：空闲超时或下游 {@code dispose()} 之后，上游 provider 那条
 * socket 仍能继续被写成功（Spring 的 {@code Flux.cache(0)} 语义，非本项目引入）。当时的处置是
 * **延后**，但要求"期间加可观测埋点"，好在上真实云端 provider 后按实测数据决定是"接受+文档"
 * 还是"换 connector / 补显式终止"。这里就给出那两个数：
 * <ul>
 *   <li>{@link #timeout()}——本轮以"超时形态"失败收尾（沿 cause 链判 {@link java.util.concurrent.TimeoutException}
 *       与 {@link java.net.http.HttpTimeoutException}，与 {@code BaseAgent.failureText} 同一口径）；</li>
 *   <li>{@link #cancelled()}——本轮被提前终止（用户点"停止生成"／前端断开连接 ⇒ {@code stopped} 置位）。</li>
 * </ul>
 * 两个数在 {@code /actuator/metrics/huoshan.agent.run.timeout} 与 {@code ...cancelled} 上可读
 * （引擎的 management.endpoints 已含 {@code metrics}，见 application.yaml / application-prod.yaml）。
 * 注意这是**累计值**，要速率就按其差分算——本类刻意不引 Prometheus 注册表（引擎没接那套）。
 * </p>
 * <p>
 * 由控制器在新建 agent 后注入（agent 是每请求新建的非 Bean 对象）；不注入时循环照常跑，只是不计数。
 * </p>
 */
@Component
public class AgentRunMetrics {

    /** 超时收尾的累计次数 */
    static final String TIMEOUT_METRIC = "huoshan.agent.run.timeout";

    /** 提前终止（停止/断连）的累计次数 */
    static final String CANCELLED_METRIC = "huoshan.agent.run.cancelled";

    private final Counter timeout;
    private final Counter cancelled;

    public AgentRunMetrics(MeterRegistry registry) {
        // Counter 按名字注册是幂等的：构造期建一次，事件路径上只 increment
        this.timeout = Counter.builder(TIMEOUT_METRIC)
                .description("以超时形态失败收尾的对话轮次（累计）")
                .register(registry);
        this.cancelled = Counter.builder(CANCELLED_METRIC)
                .description("被提前终止的对话轮次：用户停止生成或前端断开（累计）")
                .register(registry);
    }

    /** 本轮以超时收尾 */
    public void timeout() {
        timeout.increment();
    }

    /** 本轮被提前终止（停止生成 / 连接断开） */
    public void cancelled() {
        cancelled.increment();
    }
}
