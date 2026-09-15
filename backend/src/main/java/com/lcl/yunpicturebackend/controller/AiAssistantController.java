package com.lcl.yunpicturebackend.controller;

import cn.hutool.core.util.StrUtil;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.manager.ai.AiAssistantProxyManager;
import com.lcl.yunpicturebackend.manager.ai.AiAssistantTicketManager;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.service.IUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
 * <p>
 * 四个门槛：Spring Session 登录态 → 凭据组两把都在场 → **两把同属一人**（2026-09-14 review R4）
 * → **一次性凭据 ticket 有效且属于会话用户**（2026-09-14 review R5）。
 * 第三个门槛只判身份一致性、不判权限：代理不做授权判定（那是图库服务端的事），
 * 但"要透传出去的 satoken 是谁的"必须与会话用户一致，否则等于放行任意 token。
 * 第四个门槛挡的是跨站：本端点有真实副作用（跑一次 agent、花 LLM 额度，档 3 起还会写数据），
 * 而跨站顶层导航能带齐 Cookie 却读不到响应体——票只发给能读到响应体的页面，
 * 因此攻击者构造得出请求、却凑不齐参数（见 {@link AiAssistantTicketManager}）。
 */
@Slf4j
@Api(tags = "AI 助手接口")
@RestController
@RequestMapping("/ai/assistant")
public class AiAssistantController {

    private final IUserService userService;
    private final AiAssistantProxyManager proxyManager;
    private final AiAssistantTicketManager ticketManager;
    /** 与图库 sa-token 配置同名，避免第二处硬编码 token 名 */
    private final String tokenName;
    /** 与图库 Spring Session 的会话 Cookie 名一致（默认 SESSION） */
    private final String sessionCookieName;

    public AiAssistantController(IUserService userService,
                                 AiAssistantProxyManager proxyManager,
                                 AiAssistantTicketManager ticketManager,
                                 @Value("${sa-token.token-name:satoken}") String tokenName,
                                 @Value("${server.servlet.session.cookie.name:SESSION}") String sessionCookieName) {
        this.userService = userService;
        this.proxyManager = proxyManager;
        this.ticketManager = ticketManager;
        this.tokenName = tokenName;
        this.sessionCookieName = sessionCookieName;
    }

    /**
     * 签发一次性凭据（R5）：前端在每次建流之前先用 axios 取票，再把票拼进 chat 的 URL。
     * <p>
     * 用 POST 而不是 GET：签发本身有副作用（写一个 60 秒的 Redis 键），而 GET 会被跨站顶层导航触发。
     * 这一层与 chat 用同一套登录门槛——票只发给已登录、且凭据自洽的调用者。
     */
    @ApiOperation("签发 AI 助手一次性凭据")
    @PostMapping("/ticket")
    public BaseResponse<String> issueTicket(HttpServletRequest request) {
        TrustedCaller caller = requireTrustedCaller(request);
        return ResultUtils.success(ticketManager.issue(caller.user().getId()));
    }

    @ApiOperation("AI 助手对话（SSE 流式）")
    @GetMapping("/chat")
    public SseEmitter chat(String message, String chatId, String ticket, HttpServletRequest request) {
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "message 不能为空");

        // 门槛一~三：Spring Session 登录态 + 凭据组两把在场 + 两把同属一人
        TrustedCaller caller = requireTrustedCaller(request);

        // 门槛四：一次性凭据（R5）——跨站顶层导航带得来 Cookie，却读不到响应体、拿不到票
        checkTicket(ticket, caller.user());

        log.info("AI 助手对话开始, userId={}, chatId={}", caller.user().getId(), chatId);
        return proxyManager.chat(message, caller.user().getId(), chatId, caller.satoken(),
                caller.sessionCookie(), callerRole(caller.user()));
    }

    /**
     * 调用者角色（T17）：透传给引擎裁剪工具集（只有管理员挂看图打标工具）。
     * <p>
     * 代理在这里只是**如实转述**登录用户的角色，不据此判权限——打标能力本身就是管理员权限，
     * 且引擎那个工具只读、以调用者自己的凭据打图库 API，RBAC 仍由图库服务端判。
     * 角色为空的存量数据按最低角色发，不默认给管理员。
     * </p>
     */
    private String callerRole(User user) {
        return StrUtil.blankToDefault(user.getUserRole(), UserConstant.DEFAULT_ROLE);
    }

    /**
     * 门槛一~三（chat 与签发端点共用）：Spring Session 登录态 → 凭据组两把都在场 → 两把同属一人。
     * 拆出来是为了让签发端点与对话端点用同一套判据，不出现"签发比对话松"的缝。
     */
    private TrustedCaller requireTrustedCaller(HttpServletRequest request) {
        // 门槛一：Spring Session 登录态（getLoginUser 内部用 getSession(false)：匿名请求不建会话）
        User loginUser = userService.getLoginUser(request);

        // 门槛二：凭据组两把都必须在场（缺任一把到图库会退化成工具调用阶段的 40100/40102）
        String satoken = resolveSatoken(request);
        ThrowUtils.throwIf(StrUtil.isBlank(satoken), ErrorCode.NOT_LOGIN_ERROR, "登录态不完整（缺少 satoken），请重新登录");
        String sessionCookie = cookieValue(request, sessionCookieName);
        ThrowUtils.throwIf(StrUtil.isBlank(sessionCookie), ErrorCode.NOT_LOGIN_ERROR, "登录态不完整（缺少会话 Cookie），请重新登录");

        // 门槛三：satoken 必须真是这名用户登录产出的那把
        checkCredentialsBelongToSameUser(loginUser, satoken);

        return new TrustedCaller(loginUser, satoken, sessionCookie);
    }

    /** 一次通过门槛的调用者：用户 + 一组待透传给引擎的凭据（只在请求生命周期内存流转） */
    private record TrustedCaller(User user, String satoken, String sessionCookie) {
    }

    /**
     * 一致性校验：待透传给引擎的 satoken（Sa-Token space 登录态）必须与 Spring Session 里的登录用户同属一人。
     * <p>
     * 2026-09-14 独立 review（R4）实测：代理原先只判"satoken 非空白"，于是真会话 + 随手编的 token 也能让引擎
     * 读到真实空间——`listSpaces` 一类路径只由 Spring Session 授权，不会替我们发现这种错配
     * （空间维度读图有 `checkSpaceViewPermission`，但覆盖面仅限那里）。
     * 口径与 {@code checkSpaceViewPermission} 对齐：**没有有效 space 登录态 → 40100；有但不属于同一人 → 40102**。
     * 权限仍不在这里判，能不能看某个空间由图库服务端判。
     */
    private void checkCredentialsBelongToSameUser(User loginUser, String satoken) {
        Object spaceLoginId = StpKit.SPACE.getLoginIdByToken(satoken);
        ThrowUtils.throwIf(spaceLoginId == null, ErrorCode.NOT_LOGIN_ERROR,
                "登录态已失效（satoken 无效或已过期），请重新登录");
        ThrowUtils.throwIf(!loginUser.getId().toString().equals(spaceLoginId.toString()), ErrorCode.SPACE_NOT_LOGIN,
                "登录态不一致（satoken 与会话不属于同一账号），请重新登录");
    }

    /**
     * 门槛四（R5）：必须带一张有效的一次性凭据，且这张票是发给当前会话用户的。
     * <p>
     * 为什么排在三个门槛之后：登录过期时仍回原来的 40100（语义更准），而且一次身份不一致
     * 不会白烧掉一张票——票是单次使用，烧掉就得让前端再取一张。
     * 为什么用 40300 而不是 401xx：这不是"没登录"，是"请求来源不可信"——跨站导航能带齐凭据，
     * 却读不到我们的响应体，因此凑不出票。
     */
    private void checkTicket(String ticket, User loginUser) {
        Long issuedTo = ticketManager.consume(ticket);
        ThrowUtils.throwIf(issuedTo == null, ErrorCode.FORBIDDEN_ERROR,
                "缺少或已失效的一次性凭据，请刷新页面后重试");
        ThrowUtils.throwIf(!issuedTo.equals(loginUser.getId()), ErrorCode.FORBIDDEN_ERROR,
                "一次性凭据与会话不属于同一账号，请重新登录");
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
