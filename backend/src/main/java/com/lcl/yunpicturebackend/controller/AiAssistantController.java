package com.lcl.yunpicturebackend.controller;

import cn.hutool.core.util.StrUtil;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.manager.ai.AiAssistantProxyManager;
import com.lcl.yunpicturebackend.service.IUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * AI 助手代理端点（T10）：浏览器只与本服务说话，本服务带凭据组转发到引擎（ai/agent）。
 * <p>
 * 注意本端点**不在** {@code SaTokenConfigure} 的拦截路径内（那里只挂了
 * /picture|/space|/spaceUser|/file），未注册路径上的 Sa-Token 注解会静默失效，
 * 因此登录门槛在这里显式判，不依赖注解。凭据只在请求生命周期内存流转，不落库、不进日志。
 */
@Slf4j
@Api(tags = "AI 助手接口")
@RestController
@RequestMapping("/ai/assistant")
public class AiAssistantController {

    private final IUserService userService;
    private final AiAssistantProxyManager proxyManager;
    /** 与图库 sa-token 配置同名，避免第二处硬编码 token 名 */
    private final String tokenName;

    public AiAssistantController(IUserService userService,
                                 AiAssistantProxyManager proxyManager,
                                 @Value("${sa-token.token-name:satoken}") String tokenName) {
        this.userService = userService;
        this.proxyManager = proxyManager;
        this.tokenName = tokenName;
    }

    @ApiOperation("AI 助手对话（SSE 流式）")
    @GetMapping("/chat")
    public SseEmitter chat(String message, String chatId, HttpServletRequest request) {
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "message 不能为空");

        // 门槛一：Spring Session 登录态（getSession(false)：匿名请求不建会话）
        HttpSession session = request.getSession(false);
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_LOGIN_ERROR);
        User loginUser = userService.getLoginUser(request);

        // 门槛二：凭据组两把都必须在场（缺任一把在图库侧会退化成工具调用阶段的 40100/40102）
        String satoken = resolveSatoken(request);
        ThrowUtils.throwIf(StrUtil.isBlank(satoken), ErrorCode.NOT_LOGIN_ERROR, "登录态不完整，请重新登录");

        log.info("AI 助手对话开始, userId={}, chatId={}", loginUser.getId(), chatId);
        return proxyManager.chat(message, loginUser.getId(), chatId, satoken, session.getId());
    }

    /**
     * satoken 按声明顺序取三处：请求头 → Cookie → query。
     * 浏览器只带 Cookie（sa-token 写的是 HttpOnly Cookie，前端读不到也无需读），
     * 脚本冒烟常直接给头；两处都认，代理就不必关心调用方是哪种姿势。
     */
    private String resolveSatoken(HttpServletRequest request) {
        String fromHeader = request.getHeader(tokenName);
        if (StrUtil.isNotBlank(fromHeader)) {
            return fromHeader;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (tokenName.equals(cookie.getName()) && StrUtil.isNotBlank(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }
        return request.getParameter(tokenName);
    }
}
