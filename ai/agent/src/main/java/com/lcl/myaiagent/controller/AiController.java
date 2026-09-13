package com.lcl.myaiagent.controller;

import cn.hutool.core.util.StrUtil;
import com.lcl.myaiagent.agent.BaseAgent;
import com.lcl.myaiagent.agent.MyManus;
import com.lcl.myaiagent.chatmemory.FlowWindowBasedChatMemory;
import com.lcl.myaiagent.constant.UserConstant;
import com.lcl.myaiagent.model.po.User;
import com.lcl.myaiagent.service.ConversationService;
import com.lcl.myaiagent.service.ConversationTitleService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private FlowWindowBasedChatMemory flowWindowBasedChatMemory;

    @Resource
    private ConversationService conversationService;

    @Resource
    private ConversationTitleService conversationTitleService;

    /**
     * 获取登录用户（可能为 null）
     */
    private String getLoginUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        User user = (User) session.getAttribute(UserConstant.USER_LOGIN_STATE);
        return user != null ? user.getId() : null;
    }

    /**
     * 流式运行一个按请求新建的 Agent，并统一做会话簿记：
     * 消息落库由 Agent 内部的 Memory Advisor 在每步 LLM 调用后自动完成（读写双向），
     * 控制器不追加；这里只做会话登记与首条消息的 AI 标题生成。
     */
    private SseEmitter streamAgent(BaseAgent agent, String message, String convId,
                                   String conversationType, HttpServletRequest request) {
        SseEmitter emitter = agent.runStream(message);
        emitter.onCompletion(() -> {
            String userId = getLoginUserId(request);
            conversationService.getOrCreate(convId, userId, conversationType);
            conversationTitleService.generateForFirstMessage(convId, userId, message);
            log.info("Conversation bookkeeping done for chatId: {}", convId);
        });
        return emitter;
    }

    /**
     * 流式调用 Manus 超级智能体，支持多轮对话记忆和用户绑定
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message, String chatId, HttpServletRequest request) {
        // 前端未传 chatId 时生成一个，避免所有匿名请求共用 advisor 的默认会话，
        // 造成不同用户的记忆串到同一个会话里
        final String convId = StrUtil.isBlank(chatId) ? UUID.randomUUID().toString() : chatId;
        // 记忆由 Agent 内部的 Memory Advisor 读取（带 conversationId），
        // 控制器不再手动加载历史，避免与 advisor 注入的记忆重复
        MyManus manus = new MyManus(allTools, dashscopeChatModel, convId, flowWindowBasedChatMemory);
        return streamAgent(manus, message, convId, "manus", request);
    }
}
