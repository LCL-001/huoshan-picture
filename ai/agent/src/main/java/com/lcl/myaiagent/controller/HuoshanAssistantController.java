package com.lcl.myaiagent.controller;

import cn.hutool.core.util.StrUtil;
import com.lcl.myaiagent.agent.HuoshanAssistantAgent;
import com.lcl.myaiagent.agent.HuoshanAssistantSession;
import com.lcl.myaiagent.chatmemory.FlowWindowBasedChatMemory;
import com.lcl.myaiagent.common.ErrorCode;
import com.lcl.myaiagent.config.HuoshanAssistantProperties;
import com.lcl.myaiagent.config.OpenAiChatModels;
import com.lcl.myaiagent.exception.BusinessException;
import com.lcl.myaiagent.exception.ThrowUtils;
import com.lcl.myaiagent.tools.huoshan.HuoshanApiClient;
import com.lcl.myaiagent.tools.huoshan.HuoshanToolFactory;
import com.lcl.myaiagent.tools.mcp.McpToolCallbackResolver;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 图库助手 headless 端点（T8 档 1）：服务间 API key 鉴权 + 外部用户标识映射 + 用户真实 satoken 透传，
 * SSE 事件协议复用现有 step/answer/metrics/[DONE]（循环未改，前端折叠条按同一语义渲染）。
 * <p>
 * 调用方是图库 backend 代理（档 2 的 T10），不是浏览器；密钥与 satoken 都走请求头，绝不进 URL。
 * 本端点不做会话簿记（conversation 表的 user_id 外键指向引擎 user 表，外部身份插不进去），
 * 多轮记忆由 chat_message / chat_summary 按 conversationId 承载。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/ai/huoshan")
public class HuoshanAssistantController {

    @Resource
    private OpenAiChatModels openAiChatModels;

    @Resource
    private HuoshanAssistantProperties huoshanProperties;

    @Resource
    private FlowWindowBasedChatMemory flowWindowBasedChatMemory;

    /**
     * 搜图 MCP 服务（T9）的工具回调。用 ObjectProvider 而不是直接注入：MCP client 被关掉
     * （{@code AI_MCP_CLIENT_ENABLED=false}）或没配连接时这个 Bean 可能不存在，
     * 图库助手少了搜图工具仍应能开对话。
     */
    @Resource
    private ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;

    /** 搜图 MCP 工具回调的解析与降级（T19）：服务不可达时少一个工具照常开对话，失败后冷却期内不再重试 */
    @Resource
    private McpToolCallbackResolver mcpToolCallbackResolver;

    /**
     * 图库助手对话（SSE）。
     *
     * @param message   用户消息
     * @param userId    图库用户 id（由代理透传，仅用于会话归属；权限由图库按凭据判定）
     * @param chatId    图库侧的会话标识，为空表示新会话
     * @param sessionId 用户 Spring Session 会话标识（由代理透传；空间类接口的加强校验同时认它和 satoken）
     */
    @GetMapping("/chat")
    public SseEmitter chat(String message, String userId, String chatId,
                           @CookieValue(value = HuoshanApiClient.SESSION_COOKIE_NAME, required = false) String sessionId,
                           HttpServletRequest request) {
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "message 不能为空");
        ThrowUtils.throwIf(!openAiChatModels.hasAssistant(), ErrorCode.SYSTEM_ERROR,
                "图库助手模型未配置：请设置 app.ai.openai.assistant.api-key 与 model");

        String owner;
        try {
            owner = HuoshanAssistantSession.owner(userId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, e.getMessage());
        }

        String satoken = request.getHeader(HuoshanApiClient.SATOKEN_HEADER);
        ThrowUtils.throwIf(StrUtil.isBlank(satoken), ErrorCode.PARAMS_ERROR,
                "缺少 " + HuoshanApiClient.SATOKEN_HEADER + "：工具调用必须以用户真实登录态发出");
        // 两把凭据一起要：空间列表/详情读 Spring Session，空间维度读图还要求它与 satoken 一致，
        // 只给 satoken 会在工具调用阶段才炸出 40100/40102，不如在这里就说清楚缺什么
        ThrowUtils.throwIf(StrUtil.isBlank(sessionId), ErrorCode.PARAMS_ERROR,
                "缺少 " + HuoshanApiClient.SESSION_COOKIE_NAME + " 会话 Cookie：图库空间接口需要 Spring Session 与 satoken 同时在场");

        String conversationId = HuoshanAssistantSession.conversationId(owner, chatId);
        log.info("图库助手会话开始, owner={}, conversationId={}", owner, conversationId);

        // T17：看图打标是管理员能力（用户 2026-09-15 拍板，普通用户的助手摘掉只读打标）。
        // 角色头缺失/未知一律按普通用户处理（fail-closed，见 HuoshanAssistantSession.isAdmin）；
        // 这里刻意不把角色原文写进日志——它来自请求头，与 chatId 同属"可换行注入"的那一类输入。
        boolean canTagPictures = HuoshanAssistantSession.isAdmin(
                request.getHeader(HuoshanAssistantSession.USER_ROLE_HEADER));
        // 视觉模型没配就不挂 visionTagger：宁可让模型看见"手上没有这个工具"，也不给它一个每次都报错的工具
        ChatModel visionModel = openAiChatModels.hasVision() ? openAiChatModels.vision() : null;
        if (visionModel == null) {
            log.warn("视觉模型未配置（app.ai.openai.vision.*），本次会话不挂 visionTagger：看图打标不可用");
        } else if (!canTagPictures) {
            log.info("调用者非管理员，本次会话不挂 visionTagger（看图打标仅管理员可用）, conversationId={}",
                    conversationId);
        }
        // 搜图 MCP 工具（T9）：只挂给图库助手，MyManus 的工具表（ToolRegistration）不动。
        // MCP 服务不可达时这里降级为空数组（T19：少一个工具照常开对话，不再让整条对话失败）
        ToolCallback[] mcpTools = mcpToolCallbackResolver.resolve(mcpToolCallbackProvider::getIfAvailable);
        ToolCallback[] tools = HuoshanToolFactory.assistantTools(
                huoshanProperties.apiClient(satoken, sessionId), visionModel, canTagPictures, mcpTools);
        HuoshanAssistantAgent agent = new HuoshanAssistantAgent(tools, openAiChatModels.assistant(),
                conversationId, flowWindowBasedChatMemory);
        return agent.runStream(message);
    }
}
