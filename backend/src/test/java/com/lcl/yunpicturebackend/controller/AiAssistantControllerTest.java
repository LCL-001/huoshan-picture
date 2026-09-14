package com.lcl.yunpicturebackend.controller;

import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.ai.AiAssistantProxyManager;
import com.lcl.yunpicturebackend.service.IUserService;
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
 * 以及"缺任一把在代理入口即拒"（fail-closed，不把请求打到引擎）。
 * 纯单测：MockHttpServletRequest + Mockito，不启 Spring、不依赖 MySQL/Redis，进门禁。
 */
class AiAssistantControllerTest {

    private static final long USER_ID = 900900001L;
    private static final String TOKEN = "test-satoken-value";
    private static final String MESSAGE = "看看我的空间";
    /** 浏览器真实带的会话 Cookie 值形态（Spring Session 默认 Base64 编码） */
    private static final String SESSION_COOKIE_VALUE = "N2MyMGYwNWItYmJmMi00YzBiLWJjOGUtODE5NDdmY2JmOGIz";

    private IUserService userService;
    private AiAssistantProxyManager proxyManager;
    private AiAssistantController controller;

    @BeforeEach
    void setUp() {
        userService = mock(IUserService.class);
        proxyManager = mock(AiAssistantProxyManager.class);
        controller = new AiAssistantController(userService, proxyManager, "satoken", "SESSION");
        when(proxyManager.chat(any(), any(), any(), any(), any())).thenReturn(new SseEmitter());
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

        SseEmitter emitter = controller.chat(MESSAGE, "chat-1", request);

        assertThat(emitter).isNotNull();
        verify(proxyManager).chat(MESSAGE, USER_ID, "chat-1", TOKEN, SESSION_COOKIE_VALUE);
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

        controller.chat(MESSAGE, null, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE);
    }

    @Test
    void resolvesSatokenFromCookieWhenHeaderAbsent() {
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();

        controller.chat(MESSAGE, null, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE);
    }

    @Test
    void resolvesSatokenFromQueryWhenHeaderAndCookieAbsent() {
        MockHttpServletRequest request = loggedInRequest();
        request.setParameter("satoken", TOKEN);

        controller.chat(MESSAGE, null, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE);
    }

    @Test
    void headerWinsOverCookie() {
        MockHttpServletRequest request = loggedInRequest();
        request.addHeader("satoken", TOKEN + "-header");

        controller.chat(MESSAGE, null, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN + "-header", SESSION_COOKIE_VALUE);
    }

    @Test
    void blankHeaderFallsBackToCookieInsteadOfForwardingBlank() {
        MockHttpServletRequest request = loggedInRequestWithSatokenCookie();
        request.addHeader("satoken", "   ");

        controller.chat(MESSAGE, null, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, SESSION_COOKIE_VALUE);
    }

    @Test
    void rejectsWhenNotLoggedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("satoken", TOKEN);
        request.setCookies(new Cookie("SESSION", SESSION_COOKIE_VALUE));
        when(userService.getLoginUser(any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_LOGIN_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }

    @Test
    void rejectsRequestWithoutSatokenEverywhere() {
        MockHttpServletRequest request = loggedInRequest();

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, request))
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

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, request))
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

        assertThatThrownBy(() -> controller.chat("   ", null, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }
}
