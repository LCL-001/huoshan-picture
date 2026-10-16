package com.lcl.yunpicturebackend.controller;

import cn.dev33.satoken.SaManager;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationConfirmRequest;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationPrepareRequest;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationResultFact;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.AiOperationPrepareVO;
import com.lcl.yunpicturebackend.domain.vo.AiOperationResultVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.ai.AiAssistantOperationManager;
import com.lcl.yunpicturebackend.manager.ai.AiAssistantProxyManager;
import com.lcl.yunpicturebackend.manager.ai.AiAssistantTicketManager;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.service.IAiConversationService;
import com.lcl.yunpicturebackend.service.IUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.servlet.http.Cookie;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * T27.5 写操作 prepare / confirm 端点单测：登录门槛与 AiAssistantController 既有写法一致
 * （Spring Session 登录态 → 凭据组两把在场 → 两把同属一人），端点只做委托。
 * 纯单测（MockHttpServletRequest + Mockito），不启 Spring、不依赖 MySQL/Redis，进门禁。
 * <p>
 * satoken 用 Sa-Token 内存 DAO 播种真 token（与 {@link AiAssistantConversationControllerTest} 同一手法，
 * 无 Spring 时 SaManager 缺省即内存 DAO）；spring-test 的 MockHttpServletRequest 只有 setCookies。
 */
class AiAssistantOperationControllerTest {

    private static final long USER_ID = 100L;
    private static final long OTHER_USER_ID = 999L;
    private static final String TOKEN = "t27-operation-satoken";

    private IUserService userService;
    private AiAssistantOperationManager operationManager;
    private AiAssistantProxyManager proxyManager;
    private AiAssistantController controller;

    @BeforeEach
    void setUp() {
        userService = mock(IUserService.class);
        operationManager = mock(AiAssistantOperationManager.class);
        proxyManager = mock(AiAssistantProxyManager.class);
        controller = new AiAssistantController(userService, proxyManager,
                mock(AiAssistantTicketManager.class), "satoken", "SESSION",
                mock(IAiConversationService.class), operationManager);
    }

    @AfterEach
    void clearSeededTokens() {
        SaManager.getSaTokenDao().delete(StpKit.SPACE.splicingKeyTokenValue(TOKEN));
        SaManager.getSaTokenDao().delete(StpKit.SPACE.splicingKeyTokenValue(TOKEN + "-other"));
    }

    private User loginUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setUserRole("user");
        return user;
    }

    private MockHttpServletRequest loggedInRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        // 门槛三要真 token：Sa-Token 无 Spring 上下文时默认走内存 DAO，直接播种"100 的登录态"
        SaManager.getSaTokenDao().set(StpKit.SPACE.splicingKeyTokenValue(TOKEN), String.valueOf(USER_ID), 600L);
        request.addHeader("satoken", TOKEN);
        request.setCookies(new Cookie("SESSION", "stub-session"));
        return request;
    }

    private AiOperationPrepareRequest prepareRequest() {
        AiOperationPrepareRequest request = new AiOperationPrepareRequest();
        request.setChatId("chat-1");
        return request;
    }

    private AiOperationConfirmRequest confirmRequest() {
        AiOperationConfirmRequest request = new AiOperationConfirmRequest();
        request.setOperationId("op-1");
        request.setChatId("chat-1");
        request.setDigest("d".repeat(64));
        return request;
    }

    // ---------- prepare ----------

    @Test
    void prepareRequiresLogin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUser(any())).thenReturn(null);

        assertThatThrownBy(() -> controller.prepareOperation(prepareRequest(), request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(operationManager);
    }

    @Test
    void prepareRejectsIncompleteCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        // 缺 satoken（也不在 Cookie 里）

        assertThatThrownBy(() -> controller.prepareOperation(prepareRequest(), request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(operationManager);
    }

    @Test
    void prepareRejectsForeignSatoken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        // 播种一把属于别人的真 token：两把凭据各自有效、但不同属一人
        SaManager.getSaTokenDao().set(StpKit.SPACE.splicingKeyTokenValue(TOKEN),
                String.valueOf(OTHER_USER_ID), 600L);
        request.addHeader("satoken", TOKEN);
        request.setCookies(new Cookie("SESSION", "stub-session"));

        assertThatThrownBy(() -> controller.prepareOperation(prepareRequest(), request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(operationManager);
    }

    @Test
    void prepareDelegatesToManagerWithSessionUser() {
        MockHttpServletRequest request = loggedInRequest();
        AiOperationPrepareRequest body = prepareRequest();
        AiOperationPrepareVO vo = new AiOperationPrepareVO();
        vo.setOperationId("op-1");
        vo.setOperationType("batch_edit");
        vo.setDigest("d".repeat(64));
        when(operationManager.prepare(eq(USER_ID), any())).thenReturn(vo);

        BaseResponse<AiOperationPrepareVO> response = controller.prepareOperation(body, request);

        assertThat(response.getData()).isSameAs(vo);
        verify(operationManager).prepare(eq(USER_ID), argThat(req -> req == body));
    }

    // ---------- confirm ----------

    @Test
    void confirmRequiresLogin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUser(any())).thenReturn(null);

        assertThatThrownBy(() -> controller.confirmOperation(confirmRequest(), request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(operationManager);
    }

    @Test
    void confirmDelegatesToManagerWithSessionUser() {
        MockHttpServletRequest request = loggedInRequest();
        AiOperationConfirmRequest body = confirmRequest();
        AiOperationResultVO result = new AiOperationResultVO();
        result.setOperationType("batch_edit");
        when(operationManager.confirm(any(User.class), any(), any())).thenReturn(result);

        BaseResponse<AiOperationResultVO> response = controller.confirmOperation(body, request);

        assertThat(response.getData()).isSameAs(result);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(operationManager).confirm(userCaptor.capture(), argThat(req -> req == body), any());
        assertThat(userCaptor.getValue().getId()).isEqualTo(USER_ID);
    }

    @Test
    void confirmPassesThroughGenericFailureUnchanged() {
        MockHttpServletRequest request = loggedInRequest();
        when(operationManager.confirm(any(User.class), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR, "操作不存在、已过期或已处理"));

        assertThatThrownBy(() -> controller.confirmOperation(confirmRequest(), request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND_ERROR.getCode());
                    assertThat(e.getMessage()).isEqualTo("操作不存在、已过期或已处理");
                });
    }

    @Test
    void confirmRecordsOperationResultToEngineOnce() {
        MockHttpServletRequest request = loggedInRequest();
        AiOperationResultVO result = new AiOperationResultVO();
        result.setOperationId("op-1");
        result.setOperationType("batch_edit");
        result.setRequestedCount(2);
        result.setSummary(List.of("目标空间：Smoke（id：30）"));
        when(operationManager.confirm(any(User.class), any(), any())).thenReturn(result);

        controller.confirmOperation(confirmRequest(), request);

        // T44/T60：执行结果回灌引擎恰一次，内容为模型面说明，并带上本次操作的身份与计划摘要，
        // 绑定本次会话的 userId + chatId
        ArgumentCaptor<AiOperationResultFact> factCaptor = ArgumentCaptor.forClass(AiOperationResultFact.class);
        verify(proxyManager).recordOperationResult(eq(USER_ID), eq("chat-1"), factCaptor.capture());
        AiOperationResultFact fact = factCaptor.getValue();
        assertThat(fact.content()).isEqualTo(AiAssistantOperationManager.operationResultNote(result));
        assertThat(fact.content()).contains("[操作 op-1]", "目标空间：Smoke（id：30）");
        assertThat(fact.operationId()).isEqualTo("op-1");
        assertThat(fact.operationType()).isEqualTo("batch_edit");
        assertThat(fact.outcome()).isEqualTo(AiOperationResultFact.OUTCOME_EXECUTED);
        verifyNoMoreInteractions(proxyManager);
    }

    @Test
    void confirmStillSucceedsWhenResultRecordingFails() {
        MockHttpServletRequest request = loggedInRequest();
        AiOperationResultVO result = new AiOperationResultVO();
        result.setOperationType("batch_edit");
        result.setRequestedCount(1);
        when(operationManager.confirm(any(User.class), any(), any())).thenReturn(result);
        doThrow(new RuntimeException("引擎不可达")).when(proxyManager)
                .recordOperationResult(any(), any(), any(AiOperationResultFact.class));

        // 回灌是旁路：失败也要原样返回已执行的结果（确认与执行才是主路径）
        BaseResponse<AiOperationResultVO> response = controller.confirmOperation(confirmRequest(), request);

        assertThat(response.getData()).isSameAs(result);
    }

    /**
     * T66：只有管理器提供的已认证执行失败才回灌，但**失败语义一字不改**——
     * 同一个异常实例继续上抛，回灌失败也不影响它。
     */
    @Test
    void confirmRecordsFailureFactWithoutChangingError() {
        MockHttpServletRequest request = loggedInRequest();
        BusinessException rejected = new BusinessException(ErrorCode.NO_AUTH_ERROR, "无此空间权限");
        AiOperationResultFact trustedFact = failureFact();
        when(operationManager.confirm(any(User.class), any(), any())).thenAnswer(invocation -> {
            invocation.<Consumer<AiOperationResultFact>>getArgument(2).accept(trustedFact);
            throw rejected;
        });

        assertThatThrownBy(() -> controller.confirmOperation(confirmRequest(), request)).isSameAs(rejected);

        ArgumentCaptor<AiOperationResultFact> factCaptor = ArgumentCaptor.forClass(AiOperationResultFact.class);
        verify(proxyManager).recordOperationResult(eq(USER_ID), eq("chat-1"), factCaptor.capture());
        AiOperationResultFact fact = factCaptor.getValue();
        assertThat(fact.outcome()).isEqualTo(AiOperationResultFact.OUTCOME_FAILED);
        assertThat(fact.operationId()).isEqualTo("op-1");
        assertThat(fact).isSameAs(trustedFact);
        verifyNoMoreInteractions(proxyManager);
    }

    /** 失败回灌同样是旁路：引擎写不进去也不能把原始失败换成别的错。 */
    @Test
    void confirmFailureBackfillFailureDoesNotMaskTheOriginalError() {
        MockHttpServletRequest request = loggedInRequest();
        BusinessException rejected = new BusinessException(ErrorCode.NO_AUTH_ERROR, "无此空间权限");
        when(operationManager.confirm(any(User.class), any(), any())).thenAnswer(invocation -> {
            invocation.<Consumer<AiOperationResultFact>>getArgument(2).accept(failureFact());
            throw rejected;
        });
        doThrow(new RuntimeException("引擎不可达")).when(proxyManager)
                .recordOperationResult(any(), any(), any(AiOperationResultFact.class));

        assertThatThrownBy(() -> controller.confirmOperation(confirmRequest(), request)).isSameAs(rejected);
    }

    @Test
    void confirmRejectsUnverifiedIdentityWithoutWritingHiddenFact() {
        MockHttpServletRequest request = loggedInRequest();
        AiOperationConfirmRequest body = confirmRequest();
        body.setOperationId("not-a-real-operation]\nUSER_SUPPLIED_TEXT");
        BusinessException rejected = new BusinessException(ErrorCode.NOT_FOUND_ERROR, "操作不存在、已过期或已处理");
        when(operationManager.confirm(any(User.class), any(), any())).thenThrow(rejected);

        assertThatThrownBy(() -> controller.confirmOperation(body, request)).isSameAs(rejected);

        verifyNoInteractions(proxyManager);
    }

    @Test
    void confirmInfrastructureRejectionDoesNotManufactureFailureFact() {
        MockHttpServletRequest request = loggedInRequest();
        RuntimeException rejected = new IllegalStateException("redis unavailable");
        when(operationManager.confirm(any(User.class), any(), any())).thenThrow(rejected);

        assertThatThrownBy(() -> controller.confirmOperation(confirmRequest(), request)).isSameAs(rejected);

        verifyNoInteractions(proxyManager);
    }

    private AiOperationResultFact failureFact() {
        return new AiOperationResultFact(
                AiAssistantOperationManager.operationFailureNote("op-1", "无此空间权限"),
                "op-1", "batch_edit", AiOperationResultFact.OUTCOME_FAILED);
    }
}
