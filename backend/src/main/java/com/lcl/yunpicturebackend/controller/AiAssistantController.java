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
    /** 与图库 Spring Session 的会话 Cookie 名一致（默认 SESSION） */
    private final String sessionCookieName;

    public AiAssistantController(IUserService userService,
                                 AiAssistantProxyManager proxyManager,
                                 @Value("${sa-token.token-name:satoken}") String tokenName,
                                 @Value("${server.servlet.session.cookie.name:SESSION}") String sessionCookieName) {
        this.userService = userService;
        this.proxyManager = proxyManager;
        this.tokenName = tokenName;
        this.sessionCookieName = sessionCookieName;
    }

    @ApiOperation("AI 助手对话（SSE 流式）")
    @GetMapping("/chat")
    public SseEmitter chat(String message, String chatId, HttpServletRequest request) {
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "message 不能为空");

        // 门槛一：Spring Session 登录态（getLoginUser 内部用 getSession(false)：匿名请求不建会话）
        User loginUser = userService.getLoginUser(request);

        // 门槛二：凭据组两把都必须在场（缺任一把到图库会退化成工具调用阶段的 40100/40102）
        String satoken = resolveSatoken(request);
        ThrowUtils.throwIf(StrUtil.isBlank(satoken), ErrorCode.NOT_LOGIN_ERROR, "登录态不完整（缺少 satoken），请重新登录");
        String sessionCookie = cookieValue(request, sessionCookieName);
        ThrowUtils.throwIf(StrUtil.isBlank(sessionCookie), ErrorCode.NOT_LOGIN_ERROR, "登录态不完整（缺少会话 Cookie），请重新登录");

        log.info("AI 助手对话开始, userId={}, chatId={}", loginUser.getId(), chatId);
        return proxyManager.chat(message, loginUser.getId(), chatId, satoken, sessionCookie);
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
        String fromCookie = cookieValue(request, tokenName);
        return StrUtil.isNotBlank(fromCookie) ? fromCookie : request.getParameter(tokenName);
    }

    /**
     * 取会话 Cookie 的**原始值**（必须原样转发，不能用 {@code session.getId()}）。
     * Spring Session 默认把会话 id 做 Base64 编码后写进 Cookie，而 {@code getId()} 返回的是解码后的 id：
     * 2026-09-14 真图库冒烟实测，拿解码值打 /space/list/page/vo 回 40100（空间列表只认 Spring Session）。
     */
    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
