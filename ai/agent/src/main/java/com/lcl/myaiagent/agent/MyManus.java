package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.advisors.MyLoggerAdvisor;
import com.lcl.myaiagent.chatmemory.FlowWindowBasedChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * MyManus智能体，继承自ToolCallAgent
 * <p>
 * 这是一个全能的AI助手组件，能够解决用户提出的任何任务。
 * 通过集成多种工具，可以高效地完成复杂的请求。
 * 注意：Agent 是有状态对象（messageList/state 每轮独立），由控制器按请求手动创建，
 * 不能注册为 Spring 单例 Bean。
 * </p>
 */
public class MyManus extends ToolCallAgent {

    // 下一步提示词：会作为 USER 消息进入对话并被持久化，
    // 前端渲染历史时靠它识别并过滤 agent 内部提示
    public static final String NEXT_STEP_PROMPT_TEXT = """
            Based on the user's needs, proactively select the most appropriate tool or combination of tools.
            For complex tasks, break down the problem first, then solve it step by step with tools when useful.
            After using each tool, clearly explain the execution result in Chinese and suggest the next step.
            If key information is missing and you cannot continue safely or accurately, use the `askHuman` tool/function call to ask one clear question.
            When the task is complete, or when no tool call is needed, provide the final answer in Chinese and end this run.
            If you need to actively end the interaction, use the `terminate` tool/function call.
            """;

    /**
     * 构造函数，初始化MyManus智能体的配置
     * <p>
     * 该方法执行以下初始化操作：
     * 1. 调用父类构造函数传入可用工具
     * 2. 设置智能体名称、系统提示词和下一步提示词
     * 3. 配置最大执行步数为20
     * 4. 创建并配置ChatClient，添加日志记录器
     * </p>
     *
     * @param allTools 所有可用的工具回调数组
     * @param dashscopeChatModel DashScope聊天模型实例
     */
    public MyManus(ToolCallback[] allTools, ChatModel dashscopeChatModel, String chatId, FlowWindowBasedChatMemory flowWindowBasedChatMemory) {
        super(allTools);
        // 会话 ID 必须在构建时定下来，供记忆 Advisor 逐步读写外部记忆
        this.setConversationId(chatId);
        this.setName("MyManus");
        String SYSTEM_PROMPT = """
                You are MyManus, an all-capable AI assistant aimed at solving complex tasks for the user.
                You can call tools when needed for searching, file operations, resource downloads, PDF generation, and other tasks.
                Always answer the user in Chinese unless the user explicitly requests another language.
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        this.setNextStepPrompt(NEXT_STEP_PROMPT_TEXT);
        this.setMaxSteps(20);
        // 初始化客户端
        // 记忆走 Advisor 模式：每步 LLM 调用前由 advisor 读取外部记忆注入提示词，
        // conversationId 在 callLlm 里通过 advisor 参数传入；
        // 历史不做手动 addAll，避免与 advisor 注入的记忆重复
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(flowWindowBasedChatMemory).build())
                .build();
        this.setChatClient(chatClient);
    }
}
