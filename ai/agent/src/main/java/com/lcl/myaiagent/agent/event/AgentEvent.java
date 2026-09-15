package com.lcl.myaiagent.agent.event;

import java.util.Map;

/**
 * 引擎对话循环产出的事件（T8-hard 显式化）。
 * <p>
 * 本接口是 SSE 帧契约的**单一来源**：record 的组件名即帧里的字段名，各事件对应的事件名见
 * {@link SseAgentEventListener}。改名或增删必须同步三处——{@link SseAgentEventListener}、
 * 前端 {@code frontend/src/utils/assistantSse.ts}、代理侧测试。
 * <p>
 * 循环把 {@code step()} 的返回值直接喂给 {@link AgentEventListener}；原先靠
 * {@code BaseAgent.lastStepKind} / {@code lastThinkText} / {@code lastToolNames} 三个受保护字段
 * 在"子类 → 循环"之间传递分类与明细的旁路，已由本接口取代。
 */
public sealed interface AgentEvent permits AgentEvent.Step, AgentEvent.Answer, AgentEvent.Metrics, AgentEvent.Error, AgentEvent.Done {

    /**
     * 过程步（进前端折叠区）。
     *
     * @param kind    {@link Step#KIND_THINK} 或 {@link Step#KIND_TOOL}
     * @param name    展示名：思考步固定 {@link Step#NAME_THINK}，工具步是"工具名、工具名"
     * @param content 思考步是模型的自然语言推理，工具步是工具执行结果摘要
     */
    record Step(String kind, String name, String content) implements AgentEvent {

        public static final String KIND_THINK = "think";
        public static final String KIND_TOOL = "tool";
        public static final String NAME_THINK = "思考";

        /** 模型发起工具调用前的自然语言推理 */
        public static Step think(String content) {
            return new Step(KIND_THINK, NAME_THINK, content);
        }

        /** @param names 本步调用的工具名，多个以"、"连接（前端折叠条按原样展示） */
        public static Step tool(String names, String content) {
            return new Step(KIND_TOOL, names, content);
        }
    }

    /** 面向用户的最终回答（进回答气泡）：无工具调用的收尾、askHuman 的提问、上限与卡死提示 */
    record Answer(String content) implements AgentEvent {
    }

    /**
     * 运行级指标（[DONE] 之前一帧，前端折叠条尾部展示）。
     * values 会被平铺进帧里，**不要使用 event/kind/name/content 作为键**（会覆盖帧字段）。
     */
    record Metrics(Map<String, Object> values) implements AgentEvent {
    }

    /**
     * 失败收尾（T8-c）：provider 超时或调用失败时**替代回答帧**，且是本轮唯一的事件。
     * <p>
     * 面向用户的文案固定（原文只在日志里）：原先把原始异常文本当回答发出去，用户在气泡里读到的是
     * 厂商报错原文，而且因为循环不终止，同一段文本会重复 2-3 轮。
     * 前端按 {@code event=error} 分流到错误提示，代理 {@code relay()} 原样透传。
     */
    record Error(String content) implements AgentEvent {
    }

    /** 一条流的终止帧。每条流恰好一个，且必须是最后一帧。 */
    record Done() implements AgentEvent {
    }
}
