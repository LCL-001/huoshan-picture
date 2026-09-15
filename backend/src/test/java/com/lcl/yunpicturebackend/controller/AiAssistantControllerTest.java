package com.lcl.yunpicturebackend.controller;

import cn.dev33.satoken.SaManager;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.ai.AiAssistantProxyManager;
import com.lcl.yunpicturebackend.manager.ai.AiAssistantTicketManager;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.service.IUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T10 代理端点单测（docs/plan.md T10）：登录门槛、凭据组取法（satoken 三处 + Spring Session 会话 Cookie）、
 * "缺任一把在代理入口即拒"（fail-closed，不把请求打到引擎），以及"两把凭据必须同属一人"（review R4）
 * 与"必须带有效的一次性凭据"（review R5）。
 * 纯单测：MockHttpServletRequest + Mockito，不启 Spring、不依赖 MySQL/Redis，进门禁。
 * <p>
 * 真实 satoken 用 Sa-Token 的内存 DAO 播种（无 Spring 时 {@code SaManager} 缺省即
 * {@code SaTokenDaoDefaultImpl}），故本类校验的是真实的 {@code StpKit.SPACE.getLoginIdByToken} 语义，
 * 而不是被打桩的替身。票的 Redis 语义（取用即删、TTL）由 {@code AiAssistantProxyIntegrationTest} 用真 Redis 覆盖，
 * 这里只替身"这个票属于谁"。
 */
class AiAssistantControllerTest {

    private static final long USER_ID = 900900001L;
    /** 另一账号的 id：用于"两把凭据不属于同一人"与"票不属于同一人"的一致性负向用例 */
    private static final long OTHER_USER_ID = 900900002L;
    private static final String TOKEN = "test-satoken-value";
    private static final String MESSAGE = "看看我的空间";
    /** 有效票：由 setUp 播种为"USER_ID 取到的票"；未特别说明的用例都带它 */
    private static final String TICKET = "test-ticket-value";
    /** 浏览器真实带的会话 Cookie 值形态（Spring Session 默认 Base64 编码） */
    private static final String SESSION_COOKIE_VALUE = "N2MyMGYwNWItYmJmMi00YzBiLWJjOGUtODE5NDdmY2JmOGIz";

    private IUserService userService;
    private AiAssistantProxyManager proxyManager;
    private AiAssistantTicketManager ticketManager;
    private AiAssistantController controller;

    @BeforeEach
    void setUp() {
        userService = mock(IUserService.class);
        proxyManager = mock(AiAssistantProxyManager.class);
        ticketManager = mock(AiAssistantTicketManager.class);
        controller = new AiAssistantController(userService, proxyManager, ticketManager, "satoken", "SESSION");
        when(proxyManager.chat(any(), any(), any(), any(), any(), any())).thenReturn(new SseEmitter());
        // 常规用例里 TOKEN 就是 USER_ID 真实登录产出的那把，TICKET 就是发给 USER_ID 的那张票
        seedSpaceToken(TOKEN, USER_ID);
        when(ticketManager.consume(TICKET)).thenReturn(USER_ID);
    }

    @AfterEach
    void clearSeededTokens() {
        SaManager.getSaTokenDao().delete(StpKit.SPACE.splicingKeyTokenValue(TOKEN));
        SaManager.getSaTokenDao().delete(StpKit.SPACE.splicingKeyTokenValue(TOKEN + "-header"));
    }

    /** 播种一把"真实存在"的 satoken：无 Spring 时 Sa-Token 用内存 DAO，键名由框架自己拼 */
    private void seedSpaceToken(String token, long loginId) {
        SaManager.getSaTokenDao().set(StpKit.SPACE.splicingKeyTokenValue(token), String.valueOf(loginId), 600L);
    }

    /** 已登录请求：有 Spring Session（会话里能取到用户）+ 浏览器带的 SESSION Cookie */
    private MockHttpServletRequest loggedInRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        request.setCookies(new Cookie("SESSION", SESSION_COOKIE_VALUE));
        User loginUser = new User();
        loginUser.setId(USER_ID);
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        return request;
    }

    /** 浏览器真实姿势：satoken 与 SESSION 都在 Cookie 里 */
    private MockHttpServletRequest loggedInRequestWithSatokenCookie() {
        MockHttpServletRequest request = loggedInRequest();
        request.setCookies(new Cookie("SESSION", SESSION_COOKIE_VALUE), new Cookie("satoken", TOKEN));
        return request;
    }

    @Test
    void forwardsBothCredentialsResolvedFromBrowserRequest() {
        MockHttpServletRequest request = loggedInRequest();
        request.addHeader("satoken", TOKEN);

        SseEmitter emitter = controller.chat(MESSAGE, "chat-1", TICKET, request);

        assertThat(emitter).isNotNull();
        verify(proxyManager).chat(MESSAGE, USER_ID, "chat-1", TOKEN, SESSION_COOKIE_VALUE,
                UserConstant.DEFAULT_ROLE);
    }

    /**
     * T17：管理员会话必须把角色如实透传给引擎——引擎按它决定挂不挂看图打标工具
     * （角色是"引擎给模型看哪些工具"的输入，权限判定仍全在图库服务端）。
     */
    @Test
    void forwardsAdminRoleWhenCallerIsAdmin() {
        MockHttpServletRequest request = loggedInRequest();
        request.addHeader("satoken", TOKEN);
        User admin = new User();
        admin.setId(USER_ID);
        admin.setUserRole(UserConstant.ADMIN_ROLE);
        when(userService.getLoginUser(any())).thenReturn(admin);

        controller.chat(MESSAGE, null, TICKET, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE, UserConstant.ADMIN_ROLE);
    }

    /** 角色为空（存量数据）按最低角色发：不默认给管理员，与引擎侧"只认正面匹配"同口径 */
    @Test
    void forwardsDefaultRoleWhenCallerRoleIsBlank() {
        MockHttpServletRequest request = loggedInRequest();
        request.addHeader("satoken", TOKEN);
        User legacy = new User();
        legacy.setId(USER_ID);
        legacy.setUserRole("   ");
        when(userService.getLoginUser(any())).thenReturn(legacy);

        controller.chat(MESSAGE, null, TICKET, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE, UserConstant.DEFAULT_ROLE);
    }

    /**
     * 回归（2026-09-14 真图库冒烟暴露）：必须原样转发浏览器带的会话 Cookie 值，
     * 不能用 session.getId()——Spring Session 的 Cookie 值是 Base64 形态，getId() 返回解码后的 id，
     * 用后者打图库会被判"未登录"（实测 /space/list/page/vo 回 40100）。
     */
    @Test
    void forwardsRawSessionCookieInsteadOfDecodedSessionId() {
        MockHttpServletRequest request = loggedInRequest();
        request.addHeader("satoken", TOKEN);
        String decodedSessionId = request.getSession(false).getId();
        assertThat(decodedSessionId).isNotEqualTo(SESSION_COOKIE_VALUE);

        controller.chat(MESSAGE, null, TICKET, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE, UserConstant.DEFAULT_ROLE);
    }

    @Test
    void resolvesSatokenFromCookieWhenHeaderAbsent() {
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();

        controller.chat(MESSAGE, null, TICKET, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE, UserConstant.DEFAULT_ROLE);
    }

    @Test
    void resolvesSatokenFromQueryWhenHeaderAndCookieAbsent() {
        MockHttpServletRequest request = loggedInRequest();
        request.setParameter("satoken", TOKEN);

        controller.chat(MESSAGE, null, TICKET, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE, UserConstant.DEFAULT_ROLE);
    }

    @Test
    void headerWinsOverCookie() {
        MockHttpServletRequest request = loggedInRequest();
        request.addHeader("satoken", TOKEN + "-header");
        seedSpaceToken(TOKEN + "-header", USER_ID);

        controller.chat(MESSAGE, null, TICKET, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN + "-header", SESSION_COOKIE_VALUE,
                UserConstant.DEFAULT_ROLE);
    }

    @Test
    void blankHeaderFallsBackToCookieInsteadOfForwardingBlank() {
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();
        request.addHeader("satoken", "   ");

        controller.chat(MESSAGE, null, TICKET, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE, UserConstant.DEFAULT_ROLE);
    }

    @Test
    void rejectsWhenNotLoggedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("satoken", TOKEN);
        request.setCookies(new Cookie("SESSION", SESSION_COOKIE_VALUE));
        when(userService.getLoginUser(any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, TICKET, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_LOGIN_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }

    @Test
    void rejectsRequestWithoutSatokenEverywhere() {
        MockHttpServletRequest request = loggedInRequest();

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, TICKET, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少 satoken")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_LOGIN_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }

    /** 只带 satoken、不带会话 Cookie：必须在代理入口拦住，不能让引擎/工具去兜底 */
    @Test
    void rejectsRequestWithoutSessionCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        request.addHeader("satoken", TOKEN);
        User loginUser = new User();
        loginUser.setId(USER_ID);
        when(userService.getLoginUser(any())).thenReturn(loginUser);

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, TICKET, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少会话 Cookie")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_LOGIN_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }

    @Test
    void rejectsBlankMessageBeforeAnyCredentialWork() {
        MockHttpServletRequest request = loggedInRequest();
        request.addHeader("satoken", TOKEN);

        assertThatThrownBy(() -> controller.chat("   ", null, TICKET, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }

    /**
     * R4 复现（2026-09-14 独立 review 实测的洞）：真会话 + **随手编的** satoken 曾被照常转发，
     * 引擎拿着它去读真实空间——因为 satoken 只在图库服务端判 RBAC，代理原先只判"非空白"。
     * 现在必须在入口拦住，且不产生任何上游请求。
     */
    @Test
    void rejectsBogusSatokenEvenWithRealSession() {
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();
        request.setCookies(new Cookie("SESSION", SESSION_COOKIE_VALUE), new Cookie("satoken", "review-bogus-token"));

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, TICKET, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_LOGIN_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }

    /** 两把凭据都真实有效，但分属不同账号：一致性校验必须拦住（40102，与空间维度读图同一口径） */
    @Test
    void rejectsSatokenBelongingToAnotherUser() {
        seedSpaceToken(TOKEN, OTHER_USER_ID);
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, TICKET, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SPACE_NOT_LOGIN.getCode());
        verifyNoInteractions(proxyManager);
    }

    /**
     * R5 复现（2026-09-14 独立 review 实测的洞）：跨站顶层导航能带齐两把 Cookie、却读不到响应体，
     * 于是"不带票"的请求在修复前会被照常转发——替受害者跑一次 agent、花他的 LLM 额度（档 3 起还会写数据）。
     * 修复后必须在入口回 40300 且零上游请求：攻击者构造得出请求，凑不齐参数。
     */
    @Test
    void rejectsChatWithoutTicket() {
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, null, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }

    /** 编造的票（或已过期被 Redis 回收）：同样拒，且不产生上游请求 */
    @Test
    void rejectsChatWithUnknownTicket() {
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, "review-bogus-ticket", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }

    /** 票本身真实有效，但发给的是另一个账号：与"两把凭据同属一人"同一口径，必须拒 */
    @Test
    void rejectsChatWithTicketIssuedToAnotherUser() {
        when(ticketManager.consume("other-user-ticket")).thenReturn(OTHER_USER_ID);
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, "other-user-ticket", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一账号")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }

    /** 单次使用：同一张票第二次必被拒（真实实现里 consume 是取用即删，第二次返回 null） */
    @Test
    void rejectsChatWithAlreadyUsedTicket() {
        when(ticketManager.consume("single-use-ticket")).thenReturn(USER_ID, null);

        controller.chat(MESSAGE, null, "single-use-ticket", loggedInRequestWithSatokenCookie());
        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE, UserConstant.DEFAULT_ROLE);

        MockHttpServletRequest replay = loggedInRequestWithSatokenCookie();
        assertThatThrownBy(() -> controller.chat(MESSAGE, null, "single-use-ticket", replay))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN_ERROR.getCode());
    }

    /** 有效票 + 凭据齐全：转发行为与加票之前一致（票只做门槛，不改动转发内容） */
    @Test
    void acceptsChatWithValidTicket() {
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();

        SseEmitter emitter = controller.chat(MESSAGE, null, TICKET, request);

        assertThat(emitter).isNotNull();
        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE, UserConstant.DEFAULT_ROLE);
    }

    // ---------- 签发端点（POST /ai/assistant/ticket） ----------

    /** 签发与对话共用门槛：未登录不签（否则等于给未授权调用者发入场券） */
    @Test
    void issueTicketRejectsWhenNotLoggedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        request.addHeader("satoken", TOKEN);
        request.setCookies(new Cookie("SESSION", SESSION_COOKIE_VALUE));
        when(userService.getLoginUser(any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        assertThatThrownBy(() -> controller.issueTicket(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_LOGIN_ERROR.getCode());
        verifyNoInteractions(ticketManager);
    }

    /** 签发与对话共用门槛：两把凭据不属于同一人也不签（否则票会替错配的凭据背书） */
    @Test
    void issueTicketRejectsWhenCredentialsBelongToDifferentUsers() {
        seedSpaceToken(TOKEN, OTHER_USER_ID);
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();

        assertThatThrownBy(() -> controller.issueTicket(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SPACE_NOT_LOGIN.getCode());
        verifyNoInteractions(ticketManager);
    }

    /** 正常签发：返回给调用者的票绑定在他自己的 id 上 */
    @Test
    void issueTicketReturnsTicketBoundToCaller() {
        when(ticketManager.issue(USER_ID)).thenReturn("fresh-ticket");
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();

        BaseResponse<String> response = controller.issueTicket(request);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo("fresh-ticket");
    }
}
