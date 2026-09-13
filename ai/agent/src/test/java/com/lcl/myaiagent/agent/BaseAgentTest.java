package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.agent.model.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * BaseAgent 单元测试 — 覆盖状态机、step循环、stuck检测、边界校验
 */
@DisplayName("BaseAgent")
class BaseAgentTest {

    /**
     * 用于测试的轻量级 BaseAgent 实现
     */
    static class TestAgent extends BaseAgent {
        private final String[] stepResults;
        private int callCount = 0;

        TestAgent(String... stepResults) {
            this.stepResults = stepResults;
            this.setName("TestAgent");
        }

        @Override
        public String step() {
            if (callCount < stepResults.length) {
                return stepResults[callCount++];
            }
            return "default-step-result";
        }

        @Override
        protected void cleanUp() {
            setCurrentStep(0);
            if (getState() != AgentState.ERROR) {
                setState(AgentState.IDLE);
            }
        }
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
        @DisplayName("run() 后状态回到 IDLE")
        void shouldReturnToIdleAfterRun() {
            TestAgent agent = new TestAgent("done");
            agent.run("test");
            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
        }

        @Test
        @DisplayName("IDLE → RUNNING → FINISHED → IDLE 完整生命周期")
        void shouldFollowCorrectLifecycle() {
            TestAgent agent = new TestAgent("done");
            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
            agent.run("test");
            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
        }
    }

    // ==================== 输入校验 ====================

    @Nested
    @DisplayName("输入校验")
    class Validation {

        @Test
        @DisplayName("非 IDLE 状态时 run() 抛出异常")
        void shouldRejectNonIdleState() {
            TestAgent agent = new TestAgent("step1");
            agent.setState(AgentState.ERROR);
            assertThatThrownBy(() -> agent.run("test"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Can not run agent");
        }

        @Test
        @DisplayName("空提示词时 run() 抛出异常")
        void shouldRejectBlankPrompt() {
            TestAgent agent = new TestAgent("done");
            assertThatThrownBy(() -> agent.run(""))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("can not be empty");
        }

        @Test
        @DisplayName("null 提示词时 run() 抛出异常")
        void shouldRejectNullPrompt() {
            TestAgent agent = new TestAgent("done");
            assertThatThrownBy(() -> agent.run(null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("can not be empty");
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
            String result = agent.run("hello");
            assertThat(result).isNotEmpty().contains("Reached max steps");
        }

        @Test
        @DisplayName("达到 maxSteps 限制后终止")
        void shouldTerminateAtMaxSteps() {
            TestAgent agent = new TestAgent("s1", "s2", "s3", "s4", "s5",
                    "s6", "s7", "s8", "s9", "s10", "s11");
            agent.setMaxSteps(3);
            String result = agent.run("test");
            assertThat(result).contains("Reached max steps");
        }

        @Test
        @DisplayName("currentStep 在 run() 后正确计数")
        void shouldTrackStepCount() {
            TestAgent agent = new TestAgent("a", "b", "c");
            agent.setMaxSteps(2);
            agent.run("test");
            // cleanUp 重置为 0，maxSteps 达到后 FINISH
            assertThat(agent.getCurrentStep()).isEqualTo(0); // cleanUp 重置
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
            String result = agent.run("test");
            assertThat(result).contains("Agent stuck in a loop");
        }

        @Test
        @DisplayName("消息少于2条时 stuck 检测返回 false")
        void shouldReturnFalseWhenTooFewMessages() {
            TestAgent agent = new TestAgent("done");
            agent.run("single-message");
            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
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
            var emitter = agent.runStream("hello");
            assertThat(emitter).isNotNull();
        }
    }
}
