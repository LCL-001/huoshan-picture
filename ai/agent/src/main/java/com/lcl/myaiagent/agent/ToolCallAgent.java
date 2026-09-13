package com.lcl.myaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.myaiagent.agent.model.AgentState;
import com.lcl.myaiagent.tools.AskHumanTool;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具调用智能体，继承自ReActAgent
 * <p>
 * 该类实现了基于LLM的工具调用功能，能够根据上下文自动选择合适的工具执行任务。
 * 通过集成ToolCallingManager和ToolCallback机制，支持动态工具管理和执行。
 * </p>
 */
@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class ToolCallAgent extends ReActAgent {

    /**
     * think() 异常时写入消息列表的错误回复前缀。
     * 声明为常量：DeepResearchAgent 报告兜底逻辑靠它区分"错误收尾"与"报告收尾"。
     */
    public static final String STEP_ERROR_PREFIX = "处理时遇到错误：";

    /**
     * 可用的工具回调数组，包含所有可供智能体调用的工具
     */
    private ToolCallback[] availableTools;

    /**
     * 存储最近一次LLM的响应结果，包含工具调用信息
     */
    private ChatResponse toolCallChatResponse;

    /**
     * 工具调用管理器，负责工具的注册、解析和执行
     */
    private ToolCallingManager toolCallingManager;

    /**
     * 禁用内置的工具调用机制，自己维护上下文
     */
    private final ChatOptions chatOptions;


    /**
     * 构造函数，初始化可用工具和配置
     *
     * @param availableTools 可用的工具回调数组
     */
    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = createChatOptions();
    }

    /**
     * 调用 LLM，子类可覆盖以适配测试或不同 LLM 实现
     */
    protected ChatResponse callLlm(Prompt prompt) {
        String conversationId = getConversationId();
        return getChatClient()
                .prompt(prompt)
                .system(getSystemPrompt())
                .toolCallbacks(availableTools)
                // 把会话 ID 传给记忆 Advisor：不传会用默认会话，
                // 读到别的会话的记忆，本会话的水位线复用也不会生效
                .advisors(a -> {
                    if (conversationId != null) {
                        a.param(ChatMemory.CONVERSATION_ID, conversationId);
                    }
                })
                .call()
                .chatResponse();
    }

    /**
     * 创建 ChatOptions，子类可覆盖以适配不同 LLM
     * <p>
     * 工具回调必须注册在 options 上：act() 中的 ToolCallingManager.executeToolCalls
     * 依赖 Prompt 的 options 解析 ToolCallback，仅靠 think() 时 ChatClient 的
     * .toolCallbacks() 运行时合并是不够的，否则会抛 No ToolCallback found。
     * </p>
     */
    protected ChatOptions createChatOptions() {
        return DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .withToolCallbacks(List.of(availableTools))
                .build();
    }

    /**
     * 思考过程，分析当前状态并决定是否调用工具
     * <p>
     * 该方法执行以下逻辑：
     * 1. 如果有下一步提示，将其添加到消息列表
     * 2. 构建Prompt并调用LLM获取响应
     * 3. 解析响应中的工具调用信息
     * 4. 根据是否有工具调用来决定是否需要行动
     * </p>
     *
     * @return true表示需要调用工具，false表示无需调用工具
     */
    @Override
    public boolean think() {
        // 构建临时消息列表——NEXT_STEP_PROMPT 拼入请求但不持久化
        List<Message> tempMessages = new ArrayList<>(getMessageList());
        if (getNextStepPrompt() != null && !getNextStepPrompt().isEmpty()) {
            tempMessages.add(new UserMessage(getNextStepPrompt()));
            setNextStepPrompt(null);
        }
        Prompt prompt = new Prompt(tempMessages, chatOptions);
        try {
            ChatResponse chatResponse = callLlm(prompt);
            // 记录响应，用于 Act
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 输出提示信息
            String result = assistantMessage.getText();
            // 模型在发起工具调用前的自然语言推理，前端折叠区作为"思考"步骤展示
            this.lastThinkText = (result == null || result.isBlank()) ? null : result;
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
//            log.info(getName() + "的思考：" + result);
            log.info(getName() + "选择了" + toolCallList.size() + "个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s, 工具参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            if (toolCallList.isEmpty()) {
                // 无工具调用时，表示任务完成，记录助手信息并结束
                getMessageList().add(assistantMessage);
                setState(AgentState.FINISHED);
                return false;
            } else {
                // 需要调用工具时，无需记录助手信息，因为调用工具时会自动记录
                return true;
            }
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMessageList().add(new AssistantMessage(STEP_ERROR_PREFIX + e.getMessage()));
            return false;
        }
    }

    /**
     * 执行工具调用并返回结果
     * <p>
     * 该方法执行以下逻辑：
     * 1. 检查是否有工具调用
     * 2. 使用ToolCallingManager执行工具调用
     * 3. 更新对话历史，添加工具执行结果
     * 4. 提取并返回工具执行的响应信息
     * </p>
     *
     * @return 工具执行结果的描述字符串，包含各工具的完成情况和返回数据
     */
    @Override
    public String act() {
        AssistantMessage assistantMessage = toolCallChatResponse.getResult().getOutput();
        String assistantText = assistantMessage.getText();
        // 调用工具
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        // 记录上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果。
        // 防御性拷贝：conversationHistory 的实现不保证可变，而后续 think() 在无工具调用时
        // 会往消息列表 add 助手消息，不可变列表会在报告步骤抛 UnsupportedOperationException
        setMessageList(new ArrayList<>(toolExecutionResult.conversationHistory()));
        // 获取当前工具调用的结果
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        // 记录本步调用的工具名列表，供 SSE 折叠区展示
        this.lastToolNames = toolResponseMessage.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::name)
                .toList();
        String results = toolResponseMessage.getResponses().stream()
                .map(toolResponse -> "工具 " + toolResponse.name() + " 完成了它的任务！结果：" + normalizeToolResponseData(toolResponse.responseData()))
                .collect(Collectors.joining("\n"));
        String askHumanQuestion = toolResponseMessage.getResponses().stream()
                .filter(toolResponse -> "askHuman".equals(toolResponse.name()))
                .map(ToolResponseMessage.ToolResponse::responseData)
                .map(ToolCallAgent::normalizeToolResponseData)
                .filter(responseData -> responseData.startsWith(AskHumanTool.ASK_HUMAN_PREFIX))
                .map(responseData -> responseData.substring(AskHumanTool.ASK_HUMAN_PREFIX.length()))
                .findFirst()
                .orElse(null);
        if (askHumanQuestion != null) {
            // askHuman 的提问面向用户，按最终回答渲染而不是过程步骤
            this.lastStepKind = "answer";
            setState(AgentState.FINISHED);
            log.info("{} needs user clarification: {}", getName(), askHumanQuestion);
            return askHumanQuestion;
        }
        // 判断是否调用了终止工具
        if (toolResponseMessage.getResponses().stream()
                .anyMatch(toolResponse -> "doTerminate".equals(toolResponse.name()))) {
            this.lastStepKind = "answer";
            setState(AgentState.FINISHED);
            if (StrUtil.isNotBlank(assistantText)) {
                return assistantText;
            }
            return "任务结束";
        }
        log.info(getName() + "的输出：" + results);
        return results;
    }

    /**
     * 还原工具返回的原始字符串。
     * Spring AI 默认将工具的 String 返回值做 JSON 序列化，responseData 实际带首尾引号和转义符，
     * 直接做前缀判断（如 [ASK_HUMAN]）会永远匹配失败，必须先还原。
     * 历史消息组装（ChatHistoryAssembler）也需要还原responseData，故声明为 public。
     */
    public static String normalizeToolResponseData(String responseData) {
        if (responseData == null) {
            return "";
        }
        String trimmed = responseData.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"') {
            try {
                return new ObjectMapper().readValue(trimmed, String.class);
            } catch (JsonProcessingException e) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return responseData;
    }

    /**
     * 清理资源方法
     * <p>
     * 在run()或runStream()方法执行完成后清理运行时状态。
     * 该方法可能被多次调用（正常完成、超时、完成回调），因此实现具有幂等性。
     * 保留消息历史以支持多轮对话，仅重置执行控制相关的临时状态。
     * </p>
     */
    @Override
    protected void cleanUp() {
        // 重置步骤计数器，为下次运行做准备
        setCurrentStep(0);

        // 清理工具调用响应缓存（这是临时数据，可以清空）
        this.toolCallChatResponse = null;

        // 重置循环计数器
        setStuckCount(0);

        // 重置状态为空闲，允许再次运行
        // 注意：如果已经是ERROR状态，不要覆盖
        if (getState() != AgentState.ERROR) {
            setState(AgentState.IDLE);
        }

        // 重要：不清空messageList！
        // 原因：
        // 1. messageList保存了当前会话的完整对话历史
        // 2. runStream()和run()都依赖这个列表维持上下文
        // 3. 外部ChatMemory负责持久化，但内存中的历史需要保持

        log.debug("{} 资源清理完成，当前消息数: {}", getName(), getMessageList().size());
    }


}
