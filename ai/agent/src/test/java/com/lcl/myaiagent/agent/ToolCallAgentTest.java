package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.agent.event.AgentEvent;
import com.lcl.myaiagent.agent.model.AgentState;
import com.lcl.myaiagent.tools.AskHumanTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ToolCallAgent 单元测试 — 通过覆写 callLlm() 和 Mock ToolCallingManager 测试
 */
@DisplayName("ToolCallAgent")
class ToolCallAgentTest {

    private ChatResponse controlledResponse;
    private ToolCallingManager mockToolManager;
    private ToolCallAgent agent;

    @BeforeEach
    void setUp() {
        mockToolManager = mock(ToolCallingManager.class);
        setUpAgent(List.of());
    }

    void setUpAgent(List<AssistantMessage.ToolCall> toolCalls) {
        setUpAgent(toolCalls, "assistant-text");
    }

    void setUpAgent(List<AssistantMessage.ToolCall> toolCalls, String assistantText) {
        AssistantMessage msg = mock(AssistantMessage.class);
        when(msg.getText()).thenReturn(assistantText);
        when(msg.getToolCalls()).thenReturn(toolCalls);

        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(msg);

        controlledResponse = mock(ChatResponse.class);
        when(controlledResponse.getResult()).thenReturn(generation);

        agent = new ToolCallAgent(new ToolCallback[0]) {
            @Override
            protected ChatResponse callLlm(Prompt prompt) {
                return controlledResponse;
            }
        };
        agent.setName("TestAgent");
        agent.setMaxSteps(5);
        agent.setToolCallingManager(mockToolManager);
    }

    private AssistantMessage.ToolCall toolCall(String name, String arguments) {
        return new AssistantMessage.ToolCall("call-1", "FUNCTION", name, arguments);
    }

    private ToolResponseMessage.ToolResponse toolResponse(String name, String data) {
        return new ToolResponseMessage.ToolResponse("id-1", name, data);
    }

    private void mockToolExecution(ToolResponseMessage responseMsg) {
        AssistantMessage assistantMsg = agent.getToolCallChatResponse().getResult().getOutput();
        ToolExecutionResult mockResult = mock(ToolExecutionResult.class);
        when(mockResult.conversationHistory()).thenReturn(List.of(assistantMsg, responseMsg));
        when(mockToolManager.executeToolCalls(any(), any())).thenReturn(mockResult);
    }

    // ==================== think() ====================

    @Nested
    @DisplayName("think() 思考")
    class ThinkPhase {

        @Test
        @DisplayName("无工具调用 → FINISHED + return false")
        void shouldFinishWhenNoToolCalls() {
            setUpAgent(List.of());

            boolean shouldAct = agent.think();

            assertThat(shouldAct).isFalse();
            assertThat(agent.getState()).isEqualTo(AgentState.FINISHED);
        }

        @Test
        @DisplayName("有工具调用 → return true")
        void shouldReturnTrueWhenHasToolCalls() {
            setUpAgent(List.of(toolCall("webSearch", "{\"query\":\"AI\"}")));

            boolean shouldAct = agent.think();

            assertThat(shouldAct).isTrue();
            assertThat(agent.getToolCallChatResponse()).isNotNull();
        }

        @Test
        @DisplayName("nextStepPrompt 注入后置 null")
        void shouldClearNextStepPrompt() {
            setUpAgent(List.of());
            agent.setNextStepPrompt("提示语");

            agent.think();

            assertThat(agent.getNextStepPrompt()).isNull();
        }
    }

    // ==================== act() ====================

    @Nested
    @DisplayName("act() 执行")
    class ActPhase {

        @Test
        @DisplayName("普通工具 → 产出「思考 + 工具结果」两条事件（受保护字段收编的落点）")
        void shouldExecuteTool() {
            setUpAgent(List.of(toolCall("webSearch", "{}")));
            agent.think();

            mockToolExecution(ToolResponseMessage.builder()
                    .responses(List.of(toolResponse("webSearch", "搜索完成")))
                    .build());

            List<AgentEvent> events = agent.act();

            assertThat(events).hasSize(2);
            AgentEvent.Step think = (AgentEvent.Step) events.get(0);
            assertThat(think.kind()).isEqualTo(AgentEvent.Step.KIND_THINK);
            assertThat(think.name()).isEqualTo(AgentEvent.Step.NAME_THINK);
            assertThat(think.content()).isEqualTo("assistant-text");

            AgentEvent.Step tool = (AgentEvent.Step) events.get(1);
            assertThat(tool.kind()).isEqualTo(AgentEvent.Step.KIND_TOOL);
            assertThat(tool.name()).isEqualTo("webSearch");
            assertThat(tool.content()).contains("webSearch").contains("搜索完成");
        }

        @Test
        @DisplayName("模型没留自然语言推理 → 只发工具帧，不发空思考帧")
        void shouldSkipThinkFrameWhenModelSaidNothing() {
            setUpAgent(List.of(toolCall("webSearch", "{}")), "   ");
            agent.think();

            mockToolExecution(ToolResponseMessage.builder()
                    .responses(List.of(toolResponse("webSearch", "搜索完成")))
                    .build());

            List<AgentEvent> events = agent.act();

            assertThat(events).hasSize(1);
            assertThat(((AgentEvent.Step) events.get(0)).kind()).isEqualTo(AgentEvent.Step.KIND_TOOL);
        }

        @Test
        @DisplayName("askHuman → FINISHED + 一条回答事件，内容是剥掉前缀的裸问题")
        void shouldHandleAskHuman() {
            setUpAgent(List.of(toolCall("askHuman", "{}")));
            agent.think();

            mockToolExecution(ToolResponseMessage.builder()
                    .responses(List.of(toolResponse("askHuman",
                            AskHumanTool.ASK_HUMAN_PREFIX + "你的名字？")))
                    .build());

            List<AgentEvent> events = agent.act();

            assertThat(agent.getState()).isEqualTo(AgentState.FINISHED);
            assertThat(events).hasSize(1);
            // 既定行为（与 ChatHistoryAssembler 还原逻辑、前端渲染一致）：
            // askHuman 的提问面向用户，作为最终回答发出，不得带内部前缀
            String answer = ((AgentEvent.Answer) events.get(0)).content();
            assertThat(answer).isEqualTo("你的名字？");
            assertThat(answer).doesNotContain(AskHumanTool.ASK_HUMAN_PREFIX);
        }

        @Test
        @DisplayName("doTerminate → FINISHED + 以模型的自然语言收尾")
        void shouldHandleTerminate() {
            setUpAgent(List.of(toolCall("doTerminate", "{}")));
            agent.think();

            mockToolExecution(ToolResponseMessage.builder()
                    .responses(List.of(toolResponse("doTerminate", "done")))
                    .build());

            List<AgentEvent> events = agent.act();

            assertThat(agent.getState()).isEqualTo(AgentState.FINISHED);
            assertThat(events).hasSize(1);
            assertThat(((AgentEvent.Answer) events.get(0)).content()).isEqualTo("assistant-text");
        }
    }

    // ==================== cleanUp() ====================

    @Nested
    @DisplayName("cleanUp()")
    class Cleanup {

        @Test
        @DisplayName("重置状态 → IDLE")
        void shouldResetToIdle() {
            agent.setState(AgentState.FINISHED);
            agent.cleanUp();
            assertThat(agent.getState()).isEqualTo(AgentState.IDLE);
        }

        @Test
        @DisplayName("ERROR 状态保持")
        void shouldPreserveError() {
            agent.setState(AgentState.ERROR);
            agent.cleanUp();
            assertThat(agent.getState()).isEqualTo(AgentState.ERROR);
        }

        @Test
        @DisplayName("messageList 不清理")
        void shouldKeepMessages() {
            agent.getMessageList().add(
                    new org.springframework.ai.chat.messages.UserMessage("历史"));
            int size = agent.getMessageList().size();
            agent.cleanUp();
            assertThat(agent.getMessageList()).hasSize(size);
        }
    }
}
