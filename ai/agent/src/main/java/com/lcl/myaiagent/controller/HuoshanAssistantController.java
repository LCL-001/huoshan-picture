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
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
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
     * 图库助手对话（SSE）。
     *
     * @param message 用户消息
     * @param userId  图库用户 id（由代理透传，仅用于会话归属；权限由图库按 satoken 判定）
     * @param chatId  图库侧的会话标识，为空表示新会话
     */
    @GetMapping("/chat")
    public SseEmitter chat(String message, String userId, String chatId, HttpServletRequest request) {
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

        String conversationId = HuoshanAssistantSession.conversationId(owner, chatId);
        log.info("图库助手会话开始, owner={}, conversationId={}", owner, conversationId);

        ToolCallback[] tools = HuoshanToolFactory.readOnlyTools(huoshanProperties.apiClient(satoken));
        HuoshanAssistantAgent agent = new HuoshanAssistantAgent(tools, openAiChatModels.assistant(),
                conversationId, flowWindowBasedChatMemory);
        return agent.runStream(message);
    }
}
