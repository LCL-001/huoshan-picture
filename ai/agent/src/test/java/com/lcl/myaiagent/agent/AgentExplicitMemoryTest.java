package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.agent.event.RecordingAgentEventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记忆显式读写的接线守护（2026-09-27，替代 MessageChatMemoryAdvisor 的行为）。
 * <p>
 * advisor 时代每步模型调用都会重读全量记忆注入请求 + 回写"请求里最后一条用户消息"；显式化后的契约：
 * </p>
 * <ul>
 *   <li>一次运行：记忆读恰好一次（运行开始装载前缀）、用户消息写恰好一次、每步助手消息各写一次；</li>
 *   <li>发给模型的提示词 = 记忆前缀 + 本轮工作集，前缀不带 system 行（system 由 .system() 现场提供），
 *       且前缀不进 messageList（isStuck/act 的工作集口径不变）；</li>
 *   <li>chatMemory 为 null（裸 agent 单测）时全部跳过，不碰库。</li>
 * </ul>
 */
@DisplayName("记忆显式读写接线")
class AgentExplicitMemoryTest {

    private static final String CHAT_ID = "mem-test";

    @Test
    @DisplayName("一次运行：读一次、用户消息写一次、每步助手消息各写一次，提示词前缀不重复")
    void loadsOnceAndWritesExplicitly() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        chatMemory.view.add(new SystemMessage("人设行"));
        chatMemory.view.add(new AssistantMessage("上一轮的回答"));

        TestAgent agent = new TestAgent(chatMemory, "下一步提示");
        agent.toolStep(assistantWithToolCall("call-1", "listSpaces"));
        agent.finalStep("任务完成");
        mockToolExecution(agent);
        RecordingAgentEventListener listener = new RecordingAgentEventListener();

        agent.runForTest("这轮的问题", listener);

        // 读：一次运行只读一次（advisor 时代每步模型调用前都要读一次）
        assertThat(chatMemory.reads).isEqualTo(1);
        // 写：用户消息一批 + 每步助手消息一批，共 3 批；第一批是用户消息
        assertThat(chatMemory.addedBatches).hasSize(3);
        assertThat(chatMemory.addedBatches.get(0))
                .singleElement()
                .isInstanceOfSatisfying(UserMessage.class, m -> assertThat(m.getText()).isEqualTo("这轮的问题"));
        assertThat(chatMemory.addedBatches.get(1))
                .singleElement()
                .isInstanceOfSatisfying(AssistantMessage.class, m -> assertThat(m.getToolCalls()).isNotEmpty());
        assertThat(chatMemory.addedBatches.get(2))
                .singleElement()
                .isInstanceOfSatisfying(AssistantMessage.class, m -> assertThat(m.getText()).isEqualTo("任务完成"));

        // 第一步提示词 = 记忆前缀（system 行被滤掉）+ 本轮用户消息 + 下一步提示
        List<Message> first = agent.prompts.get(0).getInstructions();
        assertThat(first).hasSize(3);
        assertThat(first.get(0).getText()).isEqualTo("上一轮的回答");
        assertThat(first.get(1))
                .isInstanceOfSatisfying(UserMessage.class, m -> assertThat(m.getText()).isEqualTo("这轮的问题"));
        assertThat(first.get(2).getText()).isEqualTo("下一步提示");

        // 第二步提示词 = 同一份前缀 + 本轮工作集（用户消息、决策行、工具响应）；前缀仍只出现一次
        List<Message> second = agent.prompts.get(1).getInstructions();
        assertThat(second).hasSize(4);
        assertThat(second.get(0).getText()).isEqualTo("上一轮的回答");
        assertThat(second.get(1).getText()).isEqualTo("这轮的问题");
        assertThat(second.get(2).getText()).isBlank();                       // 工具决策行：空文本
        assertThat(second.get(3)).isInstanceOf(ToolResponseMessage.class);

        // 前缀不进工作集：messageList 里没有上一轮的行（isStuck 的扫描范围不变）
        assertThat(agent.getMessageList())
                .extracting(Message::getText)
                .doesNotContain("上一轮的回答");
    }

    @Test
    @DisplayName("chatMemory 为 null：跳过读写，提示词只有本轮消息（裸 agent 单测口径不变）")
    void skipsMemoryWhenAbsent() {
        TestAgent agent = new TestAgent(null, null);
        agent.finalStep("直接回答");
        RecordingAgentEventListener listener = new RecordingAgentEventListener();

        agent.runForTest("这轮的问题", listener);

        assertThat(agent.prompts).hasSize(1);
        assertThat(agent.prompts.get(0).getInstructions())
                .extracting(Message::getText)
                .containsExactly("这轮的问题");
        assertThat(listener.joinedAnswers()).isEqualTo("直接回答");
    }

    // ---------- 桩与工具 ----------

    /** 记录型记忆：get 返回固定视图并计数，add 按批收集 */
    private static final class RecordingChatMemory implements ChatMemory {
        final List<Message> view = new ArrayList<>();
        final List<List<Message>> addedBatches = new ArrayList<>();
        int reads;

        @Override
        public void add(String conversationId, List<Message> messages) {
            addedBatches.add(List.copyOf(messages));
        }

        @Override
        public List<Message> get(String conversationId) {
            reads++;
            return view;
        }

        @Override
        public void clear(String conversationId) {
        }
    }

    /** 主脑打桩的 ToolCallAgent：callLlm 记录每次发出的 Prompt，按脚本返回响应 */
    private static final class TestAgent extends ToolCallAgent {

        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final List<Prompt> prompts = new ArrayList<>();
        private AssistantMessage toolDecision;

        TestAgent(ChatMemory chatMemory, String nextStepPrompt) {
            super(new ToolCallback[0]);
            this.setConversationId(CHAT_ID);
            this.setChatMemory(chatMemory);
            this.setName("MemTestAgent");
            this.setNextStepPrompt(nextStepPrompt);
        }

        void toolStep(AssistantMessage decision) {
            this.toolDecision = decision;
            responses.add(response(decision));
        }

        void finalStep(String text) {
            responses.add(response(assistant(text, List.of())));
        }

        @Override
        protected ChatResponse callLlm(Prompt prompt) {
            prompts.add(prompt);
            return responses.remove();
        }

        void runForTest(String prompt, RecordingAgentEventListener listener) {
            runLoop(prompt, listener);
        }
    }

    private static void mockToolExecution(TestAgent agent) {
        ToolCallingManager toolManager = mock(ToolCallingManager.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "listSpaces", "{\"spaces\":1}")))
                .build();
        // 忠实于真实 ToolCallingManager：conversationHistory = 收到的 prompt（messageList）+ 决策行 + 工具响应，
        // 不能只回后两个——那会把 act() 里的 messageList 整个替换掉、用户消息凭空消失
        when(result.conversationHistory()).thenAnswer(inv -> {
            List<Message> history = new ArrayList<>(agent.getMessageList());
            history.add(agent.toolDecision);
            history.add(toolResponse);
            return history;
        });
        when(toolManager.executeToolCalls(any(), any())).thenReturn(result);
        agent.setToolCallingManager(toolManager);
    }

    private static AssistantMessage assistantWithToolCall(String id, String name) {
        return assistant("", List.of(new AssistantMessage.ToolCall(id, "FUNCTION", name, "{}")));
    }

    private static AssistantMessage assistant(String text, List<AssistantMessage.ToolCall> toolCalls) {
        return AssistantMessage.builder().content(text).toolCalls(toolCalls).build();
    }

    private static ChatResponse response(AssistantMessage message) {
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(message);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        return response;
    }
}
