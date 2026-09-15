package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.agent.event.AgentEvent;
import com.lcl.myaiagent.agent.model.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReActAgent 单元测试 — 测试 think→act 流转
 */
@DisplayName("ReActAgent")
class ReActAgentTest {

    /**
     * 可控的 ReActAgent 实现：可预设 think 的返回值与 act 产出的事件
     */
    static class ControlledAgent extends ReActAgent {
        private boolean thinkResult = true;
        private String actResult = "action-done";
        private boolean throwInThink = false;
        private boolean throwInAct = false;

        void setThinkResult(boolean b) { this.thinkResult = b; }
        void setActResult(String s) { this.actResult = s; }
        void setThrowInThink(boolean b) { this.throwInThink = b; }
        void setThrowInAct(boolean b) { this.throwInAct = b; }

        @Override
        public boolean think() {
            if (throwInThink) throw new RuntimeException("think failed");
            return thinkResult;
        }

        @Override
        public List<AgentEvent> act() {
            if (throwInAct) throw new RuntimeException("act failed");
            return List.of(new AgentEvent.Answer(actResult));
        }

        @Override
        protected void cleanUp() {
            setCurrentStep(0);
            if (getState() != AgentState.ERROR) setState(AgentState.IDLE);
        }
    }

    private ControlledAgent agent;

    @BeforeEach
    void setUp() {
        agent = new ControlledAgent();
        agent.setName("TestReAct");
    }

    /** step() 只产出回答事件时取它的文本，便于断言 */
    private String answerOf(List<AgentEvent> events) {
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(AgentEvent.Answer.class);
        return ((AgentEvent.Answer) events.get(0)).content();
    }

    @Nested
    @DisplayName("step() 流转")
    class StepFlow {

        @Test
        @DisplayName("think() 返回 false → 产出最后一条消息作为回答，不调用 act()")
        void shouldReturnLastMessageWhenThinkFalse() {
            agent.setThinkResult(false);
            agent.getMessageList().add(new UserMessage("user"));
            agent.getMessageList().add(new AssistantMessage("hello-world"));

            String result = answerOf(agent.step());

            assertThat(result).isEqualTo("hello-world");
        }

        @Test
        @DisplayName("think() 返回 true → 采用 act() 产出的事件")
        void shouldCallActWhenThinkTrue() {
            agent.setThinkResult(true);
            agent.setActResult("tool-executed-successfully");

            String result = answerOf(agent.step());

            assertThat(result).isEqualTo("tool-executed-successfully");
        }

        @Test
        @DisplayName("think() 抛异常 → 捕获并以回答事件告知")
        void shouldCatchThinkException() {
            agent.setThrowInThink(true);

            String result = answerOf(agent.step());

            assertThat(result).contains("步骤执行失败").contains("think failed");
        }

        @Test
        @DisplayName("act() 抛异常 → 捕获并以回答事件告知")
        void shouldCatchActException() {
            agent.setThinkResult(true);
            agent.setThrowInAct(true);

            String result = answerOf(agent.step());

            assertThat(result).contains("步骤执行失败").contains("act failed");
        }
    }

    @Nested
    @DisplayName("继承关系")
    class Inheritance {

        @Test
        @DisplayName("ReActAgent 继承 BaseAgent 的属性")
        void shouldInheritBaseAgentProperties() {
            agent.setMaxSteps(5);
            assertThat(agent.getMaxSteps()).isEqualTo(5);
            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
        }

        @Test
        @DisplayName("ReActAgent 继承 BaseAgent 的 messageList")
        void shouldInheritMessageList() {
            agent.getMessageList().add(new UserMessage("hello"));
            assertThat(agent.getMessageList()).hasSize(1);
        }
    }
}
