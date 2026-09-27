package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.advisors.MyLoggerAdvisor;
import com.lcl.myaiagent.chatmemory.FlowWindowBasedChatMemory;
import org.springframework.ai.chat.client.ChatClient;
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

    // 下一步提示词：只拼入请求、不入库（2026-09-27 起 advisor 回写移除，内部提示不再落库）；
    // 历史数据里的内部提示行仍由 ChatHistoryAssembler 按前缀过滤
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
     * @param chatModel 图库助手主脑模型（OpenAI 协议，见 OpenAiChatModels.assistant()）
     */
    public MyManus(ToolCallback[] allTools, ChatModel chatModel, String chatId, FlowWindowBasedChatMemory flowWindowBasedChatMemory) {
        super(allTools);
        // 会话 ID 与记忆在构建时定下来：跨轮历史由 runLoop 读一次作前缀、每步产出显式写回
        //（2026-09-27 起不走 MessageChatMemoryAdvisor，见 BaseAgent.chatMemory 的注释）
        this.setConversationId(chatId);
        this.setChatMemory(flowWindowBasedChatMemory);
        this.setName("MyManus");
        String SYSTEM_PROMPT = """
                You are MyManus, an all-capable AI assistant aimed at solving complex tasks for the user.
                You can call tools when needed for searching, file operations, resource downloads, PDF generation, and other tasks.
                Always answer the user in Chinese unless the user explicitly requests another language.
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        this.setNextStepPrompt(NEXT_STEP_PROMPT_TEXT);
        this.setMaxSteps(20);
        // 初始化客户端（MyLoggerAdvisor 只做日志，不改请求）
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
