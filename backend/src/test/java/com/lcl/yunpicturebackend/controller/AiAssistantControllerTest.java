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
 * T10 代理端点单测（docs/plan.md T10）：登录门槛、凭据组取法（satoken 三处 + Spring Session 会话 id）、
 * 以及"缺任一把在代理入口即拒"（fail-closed，不把请求打到引擎）。
 * 纯单测：MockHttpServletRequest + Mockito，不启 Spring、不依赖 MySQL/Redis，进门禁。
 */
class AiAssistantControllerTest {

    private static final long USER_ID = 900900001L;
    private static final String TOKEN = "test-satoken-value";
    private static final String MESSAGE = "看看我的空间";

    private IUserService userService;
    private AiAssistantProxyManager proxyManager;
    private AiAssistantController controller;

    @BeforeEach
    void setUp() {
        userService = mock(IUserService.class);
        proxyManager = mock(AiAssistantProxyManager.class);
        controller = new AiAssistantController(userService, proxyManager, "satoken");
        when(proxyManager.chat(any(), any(), any(), any(), any())).thenReturn(new SseEmitter());
    }

    /** 已登录请求：有 Spring Session，会话里能取到用户 */
    private MockHttpServletRequest loggedInRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        User loginUser = new User();
        loginUser.setId(USER_ID);
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        return request;
    }

    @Test
    void forwardsBothCredentialsResolvedFromBrowserRequest() {
        MockHttpServletRequest request = loggedInRequest();
        request.addHeader("satoken", TOKEN);
        String sessionId = request.getSession(false).getId();

        SseEmitter emitter = controller.chat(MESSAGE, "chat-1", request);

        assertThat(emitter).isNotNull();
        verify(proxyManager).chat(MESSAGE, USER_ID, "chat-1", TOKEN, sessionId);
    }

    @Test
    void resolvesSatokenFromCookieWhenHeaderAbsent() {
        MockHttpServletRequest request = loggedInRequest();
        request.setCookies(new Cookie("satoken", TOKEN));
        String sessionId = request.getSession(false).getId();

        controller.chat(MESSAGE, null, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, sessionId);
    }

    @Test
    void resolvesSatokenFromQueryWhenHeaderAndCookieAbsent() {
        MockHttpServletRequest request = loggedInRequest();
        request.setParameter("satoken", TOKEN);
        String sessionId = request.getSession(false).getId();

        controller.chat(MESSAGE, null, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, sessionId);
    }

    @Test
    void headerWinsOverCookie() {
        MockHttpServletRequest request = loggedInRequest();
        request.addHeader("satoken", "header-token");
        request.setCookies(new Cookie("satoken", "cookie-token"));

        controller.chat(MESSAGE, null, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, "header-token", request.getSession(false).getId());
    }

    @Test
    void blankHeaderFallsBackToCookieInsteadOfForwardingBlank() {
        MockHttpServletRequest request = loggedInRequest();
        request.addHeader("satoken", "   ");
        request.setCookies(new Cookie("satoken", TOKEN));

        controller.chat(MESSAGE, null, request);

        verify(proxyManager).chat(MESSAGE, USER_ID, null, TOKEN, request.getSession(false).getId());
    }

    @Test
    void rejectsRequestWithoutSpringSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("satoken", TOKEN);

        assertThatThrownBy(() -> controller.chat(MESSAGE, null, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_LOGIN_ERROR.getCode());
        verifyNoInteractions(proxyManager);
    }

    @Test
    void rejectsRequestWithSessionButNoLoginUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        request.addHeader("satoken", TOKEN);
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
                .hasMessageContaining("登录态不完整")
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
