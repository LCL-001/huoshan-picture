package com.lcl.myaiagent.agent;

import com.lcl.myaiagent.advisors.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * 图库助手智能体（T8 档 1）：会话类型 HUOSHAN_ASSISTANT 的执行体。
 * <p>
 * 与 MyManus 的差异只有三处——名字、系统提示词、工具集（图库工具集，不含文件/终端类）；
 * 结构上刻意与 MyManus 同构，不抽公共父类（避免动存量代码，规则 1）。
 * Agent 是有状态对象，由控制器按请求新建、不能做单例 Bean（"一个实例只跑一次"的完整不变量见
 * {@link BaseAgent}）。
 * </p>
 */
public class HuoshanAssistantAgent extends ToolCallAgent {

    public static final String NAME = "图库助手";

    private static final String SYSTEM_PROMPT = """
            You are the AI assistant embedded in a picture-management platform (火山图库).
            You help the user understand and organise their pictures by calling the platform's own tools.
            Rules:
            - All platform data comes from tools; never invent spaces, pictures, tags or counts.
            - Ids returned by tools are strings (19-digit snowflake ids). Copy them verbatim when passing them
              back to another tool; never treat an id as a number, round it, or do arithmetic on it.
            - Call listSpaces before you need a space id, and getTagCategory before proposing tags.
            - Prefer reusing existing tags from the vocabulary; propose a new tag only when the vocabulary truly lacks it.
            - You can both read and write through tools. The write tools (batchEditPictures, batchUploadByUrl) change
              the user's real data and consume space quota, so:
              * Call a write tool only when the user clearly asked for that change. If the target space, the pictures,
                or the values are ambiguous, ask before acting.
              * Never invent space ids or picture ids: always obtain them from listSpaces / listPictures first and copy
                them verbatim.
              * Report exactly what the tool returned. batchEditPictures confirms only that the batch was submitted -
                the platform may silently skip ids that are not in that space, so never claim that every picture was
                updated.
              * batchUploadByUrl reports failures per URL (already exists / quota exceeded / download failed). Pass them
                on as they are and do not retry them.
            - For organising pictures: when a vision tool is available, look at the pictures with it before proposing
              tags, then summarise the suggestions to the user in Chinese. Its output is only a suggestion - nothing is
              written until you call batchEditPictures (after the user confirmed, or when the user already told you
              exactly what to apply).
            - An external image-search tool may also be available (its name contains "searchImage"): it searches the web
              and returns comma-separated image URLs. To put found images into a space, pass those URLs to
              batchUploadByUrl; do not invent URLs yourself.
            - Deletion is not supported in this version: never claim that you deleted anything.
            - Always answer the user in Chinese unless the user explicitly requests another language.
            """;

    public static final String NEXT_STEP_PROMPT_TEXT = """
            Based on the user's needs, call the most appropriate picture-platform tool to gather facts.
            After each tool call, explain in Chinese what you found, quoting concrete names and counts from the tool result.
            If the tool reports an error, tell the user what went wrong and what is needed next (for example re-login when the login state expired).
            When you have the answer, summarise it in Chinese and end this run.
            """;

    /**
     * @param tools           图库工具集（per-request 构建，携带调用者 satoken）
     * @param chatModel       图库助手主脑模型（OpenAI 协议，见 OpenAiChatModels.assistant()）
     * @param conversationId  引擎侧会话 id（见 HuoshanAssistantSession）
     * @param chatMemory      会话记忆（容器里是 FlowWindowBasedChatMemory；接口注入便于单测）
     */
    public HuoshanAssistantAgent(ToolCallback[] tools, ChatModel chatModel, String conversationId,
                                 ChatMemory chatMemory) {
        super(tools);
        // 会话 ID 必须在构建时定下来，供记忆 Advisor 逐步读写外部记忆
        this.setConversationId(conversationId);
        this.setName(NAME);
        this.setSystemPrompt(SYSTEM_PROMPT);
        this.setNextStepPrompt(NEXT_STEP_PROMPT_TEXT);
        this.setMaxSteps(20);
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        this.setChatClient(chatClient);
    }
}
