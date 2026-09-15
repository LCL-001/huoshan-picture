package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.agent.event.AgentEvent;
import com.lcl.myaiagent.agent.event.RecordingAgentEventListener;
import com.lcl.myaiagent.agent.model.AgentState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * BaseAgent 单元测试 — 覆盖状态机、step循环、stuck检测、边界校验
 * <p>
 * T8-hard：{@code run()} 已退役，断言口径从"返回的结果串"改为"事件流 + 收集"；帧级契约见
 * {@code com.lcl.myaiagent.agent.event.SseEventProtocolContractTest}。
 */
@DisplayName("BaseAgent")
class BaseAgentTest {

    /**
     * 用于测试的轻量级 BaseAgent 实现：按脚本逐个吐出回答事件
     */
    static class TestAgent extends BaseAgent {
        private final String[] stepResults;
        private int callCount = 0;
        private boolean finishOnStep = false;

        TestAgent(String... stepResults) {
            this.stepResults = stepResults;
            this.setName("TestAgent");
        }

        /** 模拟"这一步就是最终回答"（生产里由 think() 无工具调用时置 FINISHED） */
        void setFinishOnStep(boolean finishOnStep) {
            this.finishOnStep = finishOnStep;
        }

        @Override
        public List<AgentEvent> step() {
            if (finishOnStep) {
                setState(AgentState.FINISHED);
            }
            if (callCount < stepResults.length) {
                return List.of(new AgentEvent.Answer(stepResults[callCount++]));
            }
            return List.of(new AgentEvent.Answer("default-step-result"));
        }

        @Override
        protected void cleanUp() {
            setCurrentStep(0);
            if (getState() != AgentState.ERROR) {
                setState(AgentState.IDLE);
            }
        }
    }

    /** 同步驱动一次运行，收回全部事件 */
    private RecordingAgentEventListener runLoop(BaseAgent agent, String userPrompt) {
        RecordingAgentEventListener listener = new RecordingAgentEventListener();
        agent.runLoop(userPrompt, listener);
        return listener;
    }

    // ==================== 状态机测试 ====================

    @Nested
    @DisplayName("状态机转换")
    class StateMachine {

        @Test
        @DisplayName("初始状态为 IDLE")
        void shouldStartWithIdleState() {
            TestAgent agent = new TestAgent("done");
            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
        }

        @Test
        @DisplayName("运行后状态回到 IDLE")
        void shouldReturnToIdleAfterRun() {
            TestAgent agent = new TestAgent("done");
            agent.setMaxSteps(1);
            runLoop(agent, "test");
            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
        }

        @Test
        @DisplayName("IDLE → RUNNING → FINISHED → IDLE 完整生命周期")
        void shouldFollowCorrectLifecycle() {
            TestAgent agent = new TestAgent("done");
            agent.setFinishOnStep(true);
            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
            runLoop(agent, "test");
            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
        }

        @Test
        @DisplayName("step 里置 FINISHED 即收尾，不再发最大步数提示")
        void shouldStopWhenStepFinishes() {
            TestAgent agent = new TestAgent("final-answer");
            agent.setFinishOnStep(true);
            agent.setMaxSteps(10);

            RecordingAgentEventListener listener = runLoop(agent, "test");

            assertThat(listener.joinedAnswers()).isEqualTo("final-answer");
            assertThat(listener.joinedAnswers()).doesNotContain("达到最大步骤");
        }

        /**
         * 上一个用例用的是 maxSteps=10，正好绕开了这个边界：最终回答**恰好落在第 maxSteps 步**时，
         * {@code step()} 已把状态置成 FINISHED，但 {@code currentStep >= maxSteps} 同时成立，
         * 于是回答气泡里会多一句"执行结束：达到最大步骤 (N)"。
         */
        @Test
        @DisplayName("最终回答恰好落在第 maxSteps 步时，不再多补一条上限提示")
        void shouldNotAppendMaxStepsNoticeWhenAnswerLandsOnTheLastStep() {
            TestAgent agent = new TestAgent("final-answer");
            agent.setFinishOnStep(true);
            agent.setMaxSteps(1);

            RecordingAgentEventListener listener = runLoop(agent, "test");

            assertThat(listener.joinedAnswers()).isEqualTo("final-answer");
        }
    }

    // ==================== 开场事件（T22） ====================

    @Nested
    @DisplayName("开场事件")
    class OpeningEvents {

        @Test
        @DisplayName("开场事件先于循环产出发出（降级提示靠它就位）")
        void openingEventsAreEmittedBeforeLoopEvents() {
            TestAgent agent = new TestAgent("final-answer");
            agent.setFinishOnStep(true);
            RecordingAgentEventListener listener = new RecordingAgentEventListener();

            agent.runLoop("test", listener, List.of(new AgentEvent.Notice("图片搜索服务暂时不可用")));

            assertThat(listener.events()).hasSize(3);
            assertThat(listener.events().get(0)).isInstanceOf(AgentEvent.Notice.class);
            assertThat(listener.events().get(1)).isInstanceOf(AgentEvent.Answer.class);
            assertThat(listener.events().get(2)).isInstanceOf(AgentEvent.Done.class);
        }

        @Test
        @DisplayName("校验失败时不发开场事件（那两条路径只回一条错误回答 + Done）")
        void openingEventsAreSkippedWhenValidationFails() {
            TestAgent agent = new TestAgent("final-answer");
            RecordingAgentEventListener listener = new RecordingAgentEventListener();

            agent.runLoop("", listener, List.of(new AgentEvent.Notice("图片搜索服务暂时不可用")));

            assertThat(listener.events()).noneMatch(AgentEvent.Notice.class::isInstance);
            assertThat(listener.joinedAnswers()).contains("用户提示不能为空");
        }
    }

    // ==================== 可观测计数（R1 埋点） ====================

    @Nested
    @DisplayName("运行计数")
    class RunMetrics {

        @Test
        @DisplayName("超时与提前终止各计一次，互不串味")
        void countsTimeoutsAndCancellations() {
            MeterRegistry registry = new SimpleMeterRegistry();

            TimeoutAgent timeoutAgent = new TimeoutAgent();
            timeoutAgent.setMetrics(new AgentRunMetrics(registry));
            runLoop(timeoutAgent, "test");

            StoppedAgent stoppedAgent = new StoppedAgent();
            stoppedAgent.setMetrics(new AgentRunMetrics(registry));
            runLoop(stoppedAgent, "test");

            assertThat(registry.get("huoshan.agent.run.timeout").counter().count())
                    .as("第一步即抛超时形态异常算一次超时")
                    .isEqualTo(1.0);
            assertThat(registry.get("huoshan.agent.run.cancelled").counter().count())
                    .as("停止标记置位算一次提前终止")
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("不注入计数器时循环照常跑（MyManus 旧链路与测试不受影响）")
        void worksWithoutMetricsInjected() {
            TestAgent agent = new TestAgent("final-answer");
            agent.setFinishOnStep(true);

            assertThat(runLoop(agent, "test").joinedAnswers()).isEqualTo("final-answer");
        }

        /** 第一步就抛"超时形态"异常（外层包着 TimeoutException，沿 cause 链判定，与 T8-c 同口径） */
        private static final class TimeoutAgent extends BaseAgent {

            @Override
            public List<AgentEvent> step() {
                throw new IllegalStateException("provider 超时", new TimeoutException("响应流空闲超时"));
            }

            @Override
            protected void cleanUp() {
                // 测试不需要额外清理
            }
        }

        /** 循环中途被置停止标记（等价于用户点"停止生成"或前端断开连接） */
        private static final class StoppedAgent extends BaseAgent {

            @Override
            public List<AgentEvent> step() {
                setStopped(true);
                return List.of(new AgentEvent.Answer("半截回答"));
            }

            @Override
            protected void cleanUp() {
                // 测试不需要额外清理
            }
        }
    }

    // ==================== 输入校验 ====================

    @Nested
    @DisplayName("输入校验")
    class Validation {

        @Test
        @DisplayName("非 IDLE 状态时以回答事件报错，不进入循环")
        void shouldRejectNonIdleState() {
            TestAgent agent = new TestAgent("step1");
            agent.setState(AgentState.ERROR);

            RecordingAgentEventListener listener = runLoop(agent, "test");

            assertThat(listener.joinedAnswers()).contains("无法从该状态运行代理");
            assertThat(agent.getMessageList()).isEmpty();
            assertThat(agent.getCurrentStep()).isZero();
        }

        @Test
        @DisplayName("空提示词时以回答事件报错")
        void shouldRejectBlankPrompt() {
            TestAgent agent = new TestAgent("done");

            RecordingAgentEventListener listener = runLoop(agent, "");

            assertThat(listener.joinedAnswers()).contains("用户提示不能为空");
        }

        @Test
        @DisplayName("null 提示词时以回答事件报错")
        void shouldRejectNullPrompt() {
            TestAgent agent = new TestAgent("done");

            RecordingAgentEventListener listener = runLoop(agent, null);

            assertThat(listener.joinedAnswers()).contains("用户提示不能为空");
        }
    }

    // ==================== Step 循环 ====================

    @Nested
    @DisplayName("Step 循环控制")
    class StepLoop {

        @Test
        @DisplayName("单步执行后达到 maxSteps 终止")
        void shouldTerminateAtMaxStepsWhenStepDoesNotFinish() {
            TestAgent agent = new TestAgent("final-answer");
            agent.setMaxSteps(1);

            RecordingAgentEventListener listener = runLoop(agent, "hello");

            assertThat(listener.joinedAnswers()).contains("达到最大步骤");
        }

        @Test
        @DisplayName("达到 maxSteps 限制后终止")
        void shouldTerminateAtMaxSteps() {
            TestAgent agent = new TestAgent("s1", "s2", "s3", "s4", "s5",
                    "s6", "s7", "s8", "s9", "s10", "s11");
            agent.setMaxSteps(3);

            RecordingAgentEventListener listener = runLoop(agent, "test");

            assertThat(listener.joinedAnswers()).contains("达到最大步骤 (3)");
        }

        @Test
        @DisplayName("currentStep 在运行后由 cleanUp 归零")
        void shouldTrackStepCount() {
            TestAgent agent = new TestAgent("a", "b", "c");
            agent.setMaxSteps(2);
            runLoop(agent, "test");
            // cleanUp 重置为 0，maxSteps 达到后 FINISH
            assertThat(agent.getCurrentStep()).isEqualTo(0);
        }
    }

    // ==================== Stuck 检测 ====================

    @Nested
    @DisplayName("循环检测")
    class StuckDetection {

        @Test
        @DisplayName("重复 ASSISTANT 消息超过阈值触发 stuck → 强制终止")
        void shouldDetectStuckAndTerminate() {
            // 预填充 messageList 模拟重复 AssistantMessage 场景
            TestAgent agent = new TestAgent("ok");
            agent.setName("StuckAgent");
            agent.setMaxSteps(5);
            // 先填充足够多的重复 ASSISTANT 消息来触发 stuck
            for (int i = 0; i < 4; i++) {
                agent.getMessageList().add(
                        new org.springframework.ai.chat.messages.UserMessage("next-step"));
                agent.getMessageList().add(
                        new org.springframework.ai.chat.messages.AssistantMessage("repeated-text"));
            }

            RecordingAgentEventListener listener = runLoop(agent, "test");

            assertThat(listener.joinedAnswers()).contains("检测到循环，智能体已终止");
        }

        @Test
        @DisplayName("消息少于2条时 stuck 检测返回 false")
        void shouldReturnFalseWhenTooFewMessages() {
            TestAgent agent = new TestAgent("done");
            agent.setFinishOnStep(true);

            runLoop(agent, "single-message");

            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
        }

        /**
         * 与 {@code shouldDetectStuckAndTerminate} 同形，但把 maxSteps 压到"卡死终止恰好落在最后一步"：
         * 旧代码会在"检测到循环，智能体已终止"之后再补一条上限提示（同源，非卡死本身的问题）。
         */
        @Test
        @DisplayName("卡死终止恰好落在第 maxSteps 步时，不再多补一条上限提示")
        void shouldNotAppendMaxStepsNoticeWhenStuckTerminationLandsOnTheLastStep() {
            TestAgent agent = new TestAgent("ok");
            agent.setName("StuckAgent");
            agent.setMaxSteps(3);
            for (int i = 0; i < 4; i++) {
                agent.getMessageList().add(
                        new org.springframework.ai.chat.messages.UserMessage("next-step"));
                agent.getMessageList().add(
                        new org.springframework.ai.chat.messages.AssistantMessage("repeated-text"));
            }

            RecordingAgentEventListener listener = runLoop(agent, "test");

            assertThat(listener.joinedAnswers()).contains("检测到循环，智能体已终止");
            assertThat(listener.joinedAnswers()).doesNotContain("达到最大步骤");
        }
    }

    // ==================== runStream ====================

    @Nested
    @DisplayName("流式执行")
    class Streaming {

        @Test
        @DisplayName("runStream() 返回非 null 的 SseEmitter")
        void shouldReturnSseEmitter() {
            TestAgent agent = new TestAgent("done");
            agent.setFinishOnStep(true);
            var emitter = agent.runStream("hello");
            assertThat(emitter).isNotNull();
        }
    }
}
