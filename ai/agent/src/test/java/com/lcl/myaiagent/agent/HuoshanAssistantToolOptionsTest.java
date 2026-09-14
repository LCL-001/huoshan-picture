package com.lcl.myaiagent.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 风险 1 守卫（docs/handoff/0011 风险 1）：工具挂载走的是 ToolCallAgent 基类的
 * createChatOptions()，返回的是 DashScope 专用的 DashScopeChatOptions。
 * 图库助手用的是 OpenAI 协议模型（OpenAiChatModel），必须确认这份 options 到模型手里
 * 仍是 ToolCallingChatOptions 且 internalToolExecutionEnabled=false——否则工具要么丢失
 * （act() 抛 No ToolCallback found），要么被模型内部执行一次、agent 再执行一次（双跑）。
 * <p>
 * 用记录型 ChatModel 桩做纯单测，不起 Spring 上下文、不连网。
 * </p>
 */
class HuoshanAssistantToolOptionsTest {

    @Test
    void toolOptionsReachModelAsProviderNeutralToolCallingOptions() {
        RecordingChatModel recordingModel = new RecordingChatModel();
        ToolCallback[] tools = new ToolCallback[]{
                stubCallback("listSpaces"), stubCallback("listPictures"), stubCallback("getTagCategory")};

        HuoshanAssistantAgent agent =
                new HuoshanAssistantAgent(tools, recordingModel, "conversation-1", new NoopChatMemory());

        agent.think();

        Prompt prompt = recordingModel.captured;
        assertThat(prompt).as("模型必须真的收到一次调用").isNotNull();
        ChatOptions options = prompt.getOptions();
        assertThat(options)
                .as("必须是 ToolCallingChatOptions，否则 OpenAiChatModel 走 ChatOptions 分支会把工具丢掉")
                .isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) options;
        assertThat(toolOptions.getInternalToolExecutionEnabled())
                .as("必须为 false：工具由 agent 自己执行，模型内部执行会造成双跑")
                .isFalse();
        Set<String> names = toolOptions.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("listSpaces", "listPictures", "getTagCategory");
    }

    /**
     * 负向控制：证明上面那条断言不是"空过"——把 internalToolExecutionEnabled 设成 true，
     * 链路必须如实把它带成 true。否则主用例里的 false 可能只是某个默认值，
     * 而"框架某天开始吞掉这个标志"这种回归就抓不到了。
     */
    @Test
    void negativeControlProvesPipelinePropagatesTheFlagInsteadOfHardcodingFalse() {
        RecordingChatModel recordingModel = new RecordingChatModel();
        InternalExecutionProbeAgent probe = new InternalExecutionProbeAgent(
                new ToolCallback[]{stubCallback("listSpaces")});
        probe.setSystemPrompt("negative-control");
        probe.setChatClient(ChatClient.builder(recordingModel).build());

        probe.think();

        ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) recordingModel.captured.getOptions();
        assertThat(toolOptions.getInternalToolExecutionEnabled())
                .as("链路必须原样传递该标志：设为 true 时也要是 true")
                .isTrue();
    }

    /**
     * 只用于负向控制：把"内部执行工具"打开，检查标志确实被带过去。
     * <p>
     * 刻意复刻基类 {@code ToolCallAgent.createChatOptions()} 的构造方式（含其调用的
     * `DashScopeChatOptions.Builder#withInternalToolExecutionEnabled` / `#withToolCallbacks`，
     * 这两个方法在 DashScope 1.1.2.0 已标注过时）——控制组的价值就在于走与生产完全同一条链路。
     * </p>
     */
    @SuppressWarnings("deprecation")
    private static final class InternalExecutionProbeAgent extends ToolCallAgent {

        InternalExecutionProbeAgent(ToolCallback[] tools) {
            super(tools);
        }

        @Override
        protected ChatOptions createChatOptions() {
            return DashScopeChatOptions.builder()
                    .withInternalToolExecutionEnabled(true)
                    .withToolCallbacks(List.of(getAvailableTools()))
                    .build();
        }
    }

    /** 记录最近一次收到的 Prompt，其余一律返回"无工具调用的普通回答" */
    private static final class RecordingChatModel implements ChatModel {

        private Prompt captured;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.captured = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage("已收到"))));
        }
    }

    /** 记忆桩：本用例只关心送进模型的 options，不需要真记忆 */
    private static final class NoopChatMemory implements ChatMemory {

        @Override
        public void add(String conversationId, List<Message> messages) {
            // 无需持久化
        }

        @Override
        public List<Message> get(String conversationId) {
            return Collections.emptyList();
        }

        @Override
        public void clear(String conversationId) {
            // 无状态
        }
    }

    private static ToolCallback stubCallback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("stub " + name)
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }
}
