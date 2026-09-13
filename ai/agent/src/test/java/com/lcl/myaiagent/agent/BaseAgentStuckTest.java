package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.agent.model.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;


import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * isStuck 和 handleStuckState 单元测试（直接操作 messageList）
 */
@DisplayName("BaseAgent 循环检测")
class BaseAgentStuckTest {

    private TestAgent agent;

    static class TestAgent extends BaseAgent {
        @Override
        public String step() { return "ok"; }

        @Override
        protected void cleanUp() {
            setCurrentStep(0);
            if (getState() != AgentState.ERROR) setState(AgentState.IDLE);
        }
    }

    @BeforeEach
    void setUp() {
        agent = new TestAgent();
        agent.setName("TestAgent");
    }

    @Test
    @DisplayName("空 messageList 时 isStuck 返回 false")
    void shouldReturnFalseForEmptyList() {
        assertThat(agent.isStuck()).isFalse();
    }

    @Test
    @DisplayName("单条 ASSISTANT 消息时 isStuck 返回 false")
    void shouldReturnFalseForSingleMessage() {
        agent.getMessageList().add(new UserMessage("user"));
        agent.getMessageList().add(new AssistantMessage("hello"));
        assertThat(agent.isStuck()).isFalse();
    }

    @Test
    @DisplayName("连续 3 条相同 ASSISTANT 消息时 isStuck 返回 true (duplicateThreshold=2)")
    void shouldDetectThreeConsecutiveSameAssistantMessages() {
        agent.getMessageList().add(new UserMessage("user"));
        agent.getMessageList().add(new AssistantMessage("repeated"));
        agent.getMessageList().add(new UserMessage("next-step"));
        agent.getMessageList().add(new AssistantMessage("repeated"));
        agent.getMessageList().add(new UserMessage("next-step"));
        agent.getMessageList().add(new AssistantMessage("repeated"));
        // duplicateCount = 2 >= threshold 2 → stuck
        assertThat(agent.isStuck()).isTrue();
    }

    @Test
    @DisplayName("不同文本的 ASSISTANT 消息不触发 isStuck")
    void shouldNotDetectDifferentMessages() {
        agent.getMessageList().add(new UserMessage("user"));
        agent.getMessageList().add(new AssistantMessage("hello"));
        agent.getMessageList().add(new UserMessage("next"));
        agent.getMessageList().add(new AssistantMessage("world"));
        agent.getMessageList().add(new UserMessage("next"));
        agent.getMessageList().add(new AssistantMessage("different"));
        assertThat(agent.isStuck()).isFalse();
    }

    @Test
    @DisplayName("handleStuckState 第 3 次调用强制终止")
    void shouldTerminateAfterThreeStuckDetections() {
        agent.setState(AgentState.RUNNING);
        agent.handleStuckState(); // stuckCount=1
        assertThat(agent.getState()).isEqualTo(AgentState.RUNNING);
        agent.handleStuckState(); // stuckCount=2
        assertThat(agent.getState()).isEqualTo(AgentState.RUNNING);
        agent.handleStuckState(); // stuckCount=3 → FINISHED
        assertThat(agent.getState()).isEqualTo(AgentState.FINISHED);
    }

    @Test
    @DisplayName("消息列表中混杂 ToolResponseMessage 时，isStuck 跳过它们查找 ASSISTANT")
    void shouldSkipToolResponseMessages() {
        agent.getMessageList().add(new UserMessage("user"));
        agent.getMessageList().add(new AssistantMessage("repeated"));
        agent.getMessageList().add(new UserMessage("next-step"));
        // 模拟工具执行结果（非 ASSISTANT 类型）
        agent.getMessageList().add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("id", "tool", "data")))
                .build());
        agent.getMessageList().add(new UserMessage("next-step"));
        agent.getMessageList().add(new AssistantMessage("repeated"));
        // 重复数：1 < threshold(2)
        assertThat(agent.isStuck()).isFalse();

        // 再加一组，重复数=2 == threshold → stuck
        agent.getMessageList().add(new UserMessage("next-step"));
        agent.getMessageList().add(new AssistantMessage("repeated"));
        assertThat(agent.isStuck()).isTrue();
    }
}
