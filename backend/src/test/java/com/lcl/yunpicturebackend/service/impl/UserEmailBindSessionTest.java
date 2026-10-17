package com.lcl.yunpicturebackend.service.impl;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStorage;
import cn.dev33.satoken.servlet.model.SaRequestForServlet;
import cn.dev33.satoken.servlet.model.SaResponseForServlet;
import cn.dev33.satoken.servlet.model.SaStorageForServlet;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.user.UserEmailBindRequest;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** T68: actual bindEmail entry point + real Sa-Token memory DAO; no database or Redis. */
class UserEmailBindSessionTest {

    private static final long USER_ID = 906800001L;
    private static final String TOKEN = "t68-email-bind-token";
    private static final String EMAIL = "new-owner@example.com";
    private SaTokenContext previousContext;
    private UserServiceImpl service;
    private MockHttpServletRequest request;
    private LambdaQueryChainWrapper<User> query;
    private LambdaUpdateChainWrapper<User> update;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousContext = SaManager.getSaTokenContext();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        service = spy(new UserServiceImpl());
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("original-owner@example.com");
        doReturn(user).when(service).getById(USER_ID);
        doNothing().when(service).verifyEmailCaptcha(anyString(), anyString());
        query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        doReturn(null).when(query).one();
        update = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
        doReturn(query).when(service).lambdaQuery();
        doReturn(update).when(service).lambdaUpdate();
        when(update.update()).thenReturn(true);
        request = new MockHttpServletRequest();
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        // Use the project's actual 1.39 servlet adapters, not mock APIs introduced in 1.42.
        SaTokenContextForThreadLocalStorage.setBox(new SaRequestForServlet(request),
                new SaResponseForServlet(response), new SaStorageForServlet(request));
        SaManager.getSaTokenDao().delete(StpKit.SPACE.splicingKeyTokenValue(TOKEN));
    }

    @AfterEach
    void tearDown() {
        SaManager.getSaTokenDao().delete(StpKit.SPACE.splicingKeyTokenValue(TOKEN));
        RequestContextHolder.resetRequestAttributes();
        SaTokenContextForThreadLocalStorage.clearBox();
        SaManager.setSaTokenContext(previousContext);
    }

    @Test
    void springSessionWithoutSaTokenCannotChangeRecoveryEmail() {
        assertRejectedBeforeEmailSideEffects(ErrorCode.SPACE_NOT_LOGIN);
    }

    @Test
    void revokedTokenCannotChangeRecoveryEmail() {
        seedToken(USER_ID);
        SaManager.getSaTokenDao().delete(StpKit.SPACE.splicingKeyTokenValue(TOKEN));
        assertRejectedBeforeEmailSideEffects(ErrorCode.SPACE_NOT_LOGIN);
    }

    @Test
    void tokenFromAnotherUserCannotChangeRecoveryEmail() {
        seedToken(USER_ID + 1);
        assertRejectedBeforeEmailSideEffects(ErrorCode.SPACE_NOT_LOGIN);
    }

    @Test
    void tokenWithoutSpringSessionCannotChangeRecoveryEmail() {
        seedToken(USER_ID);
        request.getSession().removeAttribute(UserConstant.USER_LOGIN_STATE);
        assertRejectedBeforeEmailSideEffects(ErrorCode.NOT_LOGIN_ERROR);
    }

    @Test
    void consistentHeaderTokenStillBindsEmail() {
        seedToken(USER_ID);
        assertThat(service.bindEmail(bindRequest(), request).getData()).isTrue();
        verify(service).verifyEmailCaptcha(EMAIL, "123456");
        verify(update).update();
    }

    @Test
    void consistentCookieTokenStillBindsEmail() {
        SaManager.getSaTokenDao().set(StpKit.SPACE.splicingKeyTokenValue(TOKEN),
                String.valueOf(USER_ID), 600L);
        request.setCookies(new Cookie("satoken", TOKEN));
        assertThat(service.bindEmail(bindRequest(), request).getData()).isTrue();
        verify(service).verifyEmailCaptcha(EMAIL, "123456");
        verify(update).update();
    }

    private void seedToken(long loginId) {
        SaManager.getSaTokenDao().set(StpKit.SPACE.splicingKeyTokenValue(TOKEN),
                String.valueOf(loginId), 600L);
        request.addHeader("satoken", TOKEN);
    }

    private UserEmailBindRequest bindRequest() {
        UserEmailBindRequest dto = new UserEmailBindRequest();
        dto.setEmail(EMAIL);
        dto.setEmailCode("123456");
        return dto;
    }

    private void assertRejectedBeforeEmailSideEffects(ErrorCode error) {
        assertThatThrownBy(() -> service.bindEmail(bindRequest(), request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(error.getCode()));
        verify(service, never()).verifyEmailCaptcha(any(), any());
        verifyNoInteractions(query, update);
    }
}
