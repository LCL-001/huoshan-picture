package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.agent.event.AgentEvent;
import com.lcl.myaiagent.agent.model.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * isStuck / handleStuckState 单元测试（直接操作 messageList）。
 * <p>
 * T33 重写后的口径：在 assistant 子序列的"循环签名"上做滑窗判定——最近 {@code STUCK_WINDOW} 步内
 * 同一签名出现 ≥ {@code duplicateThreshold} 次即判卡死。签名 = 工具调用步"tool|名|规范化参数"，
 * 纯文本步"text|文本"；空文本且无工具调用的决策行无签名。工具签名优先于文本（带文本的工具决策行
 * 按工具签名判，文本只是模型的修辞变化）。
 * </p>
 */
@DisplayName("BaseAgent 循环检测")
class BaseAgentStuckTest {

    private static final AtomicLong CALL_IDS = new AtomicLong();

    private TestAgent agent;

    static class TestAgent extends BaseAgent {
        @Override
        public List<AgentEvent> step() {
            return List.of(new AgentEvent.Answer("ok"));
        }

        @Override
        protected void cleanUp() {
            setCurrentStep(0);
            if (getState() != AgentState.IDLE && getState() != AgentState.ERROR) {
                setState(AgentState.IDLE);
            }
        }
    }

    @BeforeEach
    void setUp() {
        agent = new TestAgent();
        agent.setName("TestAgent");
    }

    // ---------- 造消息的小工具 ----------

    /** 工具决策行（文本为空，真实循环里最常见的形态） */
    private static AssistantMessage decision(String toolName, String arguments) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-" + CALL_IDS.incrementAndGet(), "FUNCTION", toolName, arguments)))
                .build();
    }

    /** 带自然语言推理的工具决策行（文本 + 工具调用同时在场） */
    private static AssistantMessage decisionWithText(String text, String toolName, String arguments) {
        return AssistantMessage.builder().content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-" + CALL_IDS.incrementAndGet(), "FUNCTION", toolName, arguments)))
                .build();
    }

    private static ToolResponseMessage toolResponse() {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("id", "tool", "data")))
                .build();
    }

    private TestAgent givenMessages(Object... messages) {
        for (Object message : messages) {
            agent.getMessageList().add((org.springframework.ai.chat.messages.Message) message);
        }
        return agent;
    }

    // ---------- 触发面：什么算循环 ----------

    @Test
    @DisplayName("空 messageList 时 isStuck 返回 false")
    void shouldReturnFalseForEmptyList() {
        assertThat(agent.isStuck()).isFalse();
    }

    @Test
    @DisplayName("单条 ASSISTANT 消息时 isStuck 返回 false")
    void shouldReturnFalseForSingleMessage() {
        givenMessages(new UserMessage("user"), new AssistantMessage("hello"));
        assertThat(agent.isStuck()).isFalse();
    }

    @Test
    @DisplayName("同签名工具调用连续重复（空文本决策行）→ true：旧实现在这里全程失明")
    void shouldDetectRepeatedToolCallSignature() {
        givenMessages(
                new UserMessage("user"),
                decision("listSpaces", "{}"),
                toolResponse(),
                new UserMessage("next"),
                decision("listSpaces", "{}"),
                toolResponse());
        assertThat(agent.isStuck()).isTrue();
    }

    @Test
    @DisplayName("纯文本回答连续重复 → true（含当前共 2 次即触发，不必等第 3 次）")
    void shouldDetectConsecutiveSameText() {
        givenMessages(
                new UserMessage("user"),
                new AssistantMessage("重复回答"),
                new UserMessage("next"),
                new AssistantMessage("重复回答"));
        assertThat(agent.isStuck()).isTrue();
    }

    @Test
    @DisplayName("交替循环（工具→文本→同工具）在滑窗内 → true")
    void shouldDetectAlternatingLoop() {
        givenMessages(
                new UserMessage("user"),
                decision("listSpaces", "{}"),
                toolResponse(),
                new UserMessage("next"),
                new AssistantMessage("换了个说法继续"),
                new UserMessage("next"),
                decision("listSpaces", "{}"),
                toolResponse());
        assertThat(agent.isStuck()).isTrue();
    }

    @Test
    @DisplayName("带文本的工具决策行按工具签名判：文本变化不影响检测")
    void toolSignatureTakesPrecedenceOverText() {
        givenMessages(
                new UserMessage("user"),
                decisionWithText("我先查询一下", "listSpaces", "{}"),
                toolResponse(),
                new UserMessage("next"),
                decisionWithText("换个说法再查", "listSpaces", "{}"),
                toolResponse());
        assertThat(agent.isStuck()).isTrue();
    }

    // ---------- 不触发面：什么不算循环 ----------

    @Test
    @DisplayName("同名工具但参数不同 → false（不是重复调用）")
    void shouldNotDetectSameToolDifferentArguments() {
        givenMessages(
                new UserMessage("user"),
                decision("listPictures", "{\"spaceId\":\"1\"}"),
                toolResponse(),
                new UserMessage("next"),
                decision("listPictures", "{\"spaceId\":\"2\"}"),
                toolResponse());
        assertThat(agent.isStuck()).isFalse();
    }

    @Test
    @DisplayName("不同签名交错 → false")
    void shouldNotDetectDifferentMessages() {
        givenMessages(
                new UserMessage("user"),
                new AssistantMessage("hello"),
                new UserMessage("next"),
                new AssistantMessage("world"),
                new UserMessage("next"),
                new AssistantMessage("different"));
        assertThat(agent.isStuck()).isFalse();
    }

    @Test
    @DisplayName("重复落在滑窗之外（间隔 ≥4 步）→ false：全局计数口径在这里是误报")
    void shouldNotDetectRepeatsOutsideWindow() {
        // assistant 子序列 = A B C D A F G H A：A 出现 3 次但相邻两次间隔 4 步，窗口内只看得到 1 次
        givenMessages(
                new UserMessage("u"), new AssistantMessage("A"),
                new UserMessage("u"), new AssistantMessage("B"),
                new UserMessage("u"), new AssistantMessage("C"),
                new UserMessage("u"), new AssistantMessage("D"),
                new UserMessage("u"), new AssistantMessage("A"),
                new UserMessage("u"), new AssistantMessage("F"),
                new UserMessage("u"), new AssistantMessage("G"),
                new UserMessage("u"), new AssistantMessage("H"),
                new UserMessage("u"), new AssistantMessage("A"));
        assertThat(agent.isStuck()).isFalse();
    }

    @Test
    @DisplayName("空文本且无工具调用的决策行无签名，不参与计数")
    void shouldIgnoreBlankDecisionRowsWithoutToolCalls() {
        givenMessages(
                new UserMessage("user"),
                AssistantMessage.builder().content("").build(),
                new UserMessage("next"),
                AssistantMessage.builder().content("  ").build());
        assertThat(agent.isStuck()).isFalse();
    }

    // ---------- handleStuckState：第一次纠偏、第二次强停 ----------

    @Test
    @DisplayName("第 1 次卡死：纠偏提示注入，状态仍 RUNNING")
    void firstStuckNudges() {
        agent.setState(AgentState.RUNNING);

        agent.handleStuckState();

        assertThat(agent.getState()).isEqualTo(AgentState.RUNNING);
        assertThat(agent.getNextStepPrompt()).startsWith(BaseAgent.STUCK_PROMPT_PREFIX);
        assertThat(agent.getStuckCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("第 2 次卡死：强制终止（旧实现要等第 3 次，浪费约 2 步预算）")
    void secondStuckForceTerminates() {
        agent.setState(AgentState.RUNNING);
        agent.handleStuckState();

        agent.handleStuckState();

        assertThat(agent.getState()).isEqualTo(AgentState.FINISHED);
    }
}
