package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.agent.event.AgentEvent;
import com.lcl.myaiagent.agent.event.AgentEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T45 全循环守护：确认卡片发出后，Agent 必须**确定性收尾**——不再多走一步模型调用。
 * <p>
 * {@code ToolCallAgentTest} 只测到 {@code act()} 产出的帧；本类把 prepare 载荷放进 {@code runLoop}，
 * 断言循环结构本身：主模型恰调用一次、恰一张确认卡片、恰一个 {@code Done}、终态 FINISHED。
 * 修复前（{@code act()} 未置 FINISHED）会再调模型、多发一张卡片。
 * </p>
 */
class ToolCallAgentConfirmationLoopTest {

    /** 记录型监听器：按序收下循环产出的每个事件 */
    private static final class Recorder implements AgentEventListener {
        final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void onEvent(AgentEvent event) {
            events.add(event);
        }
    }

    private static final String PENDING_CONFIRMATION_PAYLOAD = """
            {"status":"pending_confirmation","operationType":"batch_edit",
            "operationId":"op-1","digest":"d1","summary":["目标空间 id：30","图片：2 张","分类 → 风景"]}""";

    @Test
    void stopsLoopAfterEmittingConfirmationSoTheModelIsNotCalledAgain() {
        AssistantMessage toolCallMsg = mock(AssistantMessage.class);
        when(toolCallMsg.getText()).thenReturn("我先准备一下整理计划。");
        when(toolCallMsg.getToolCalls()).thenReturn(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "prepareBatchEditPictures", "{}")));
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(toolCallMsg);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);

        AtomicInteger llmCalls = new AtomicInteger();
        ToolCallingManager toolManager = mock(ToolCallingManager.class);
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]) {
            @Override
            protected ChatResponse callLlm(Prompt prompt) {
                llmCalls.incrementAndGet();
                return response;
            }
        };
        agent.setName("ConfirmationLoopAgent");
        agent.setMaxSteps(5);
        agent.setToolCallingManager(toolManager);

        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "id-1", "prepareBatchEditPictures", PENDING_CONFIRMATION_PAYLOAD)))
                .build();
        ToolExecutionResult executionResult = mock(ToolExecutionResult.class);
        when(executionResult.conversationHistory()).thenReturn(List.of(toolCallMsg, toolResponse));
        when(toolManager.executeToolCalls(any(), any())).thenReturn(executionResult);

        Recorder recorder = new Recorder();
        agent.runLoop("帮我把这几张图改成风景", recorder, List.of());

        assertThat(llmCalls.get()).as("确认卡片发出后不应再调主模型").isEqualTo(1);
        assertThat(recorder.events).filteredOn(AgentEvent.Confirmation.class::isInstance)
                .as("一轮对话只生成一张确认卡片").hasSize(1);
        assertThat(recorder.events).filteredOn(AgentEvent.Done.class::isInstance).hasSize(1);
        // 确认卡片即本轮终点：不再有"达到步数上限 / 重复步骤"这类收尾 Answer 帧
        // （循环若继续，这两类 Answer 之一必然出现）
        assertThat(recorder.events).filteredOn(AgentEvent.Answer.class::isInstance).isEmpty();
    }
}
