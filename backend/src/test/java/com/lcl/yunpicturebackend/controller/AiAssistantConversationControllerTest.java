package com.lcl.yunpicturebackend.controller;

import cn.dev33.satoken.SaManager;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.AiConversationVO;
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
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.Cookie;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T36 会话列表端点与 chat 建流前索引接线单测：纯单测（MockHttpServletRequest + Mockito），
 * 不启 Spring、不依赖 MySQL/Redis，进门禁。
 * <p>
 * satoken 用 Sa-Token 内存 DAO 播种真 token（与 {@link AiAssistantControllerTest} 同一手法，
 * 无 Spring 时 SaManager 缺省即内存 DAO）：登录即产出可校验的 token，校验走真实的
 * {@code StpKit.SPACE.getLoginIdByToken} 语义；ticket 直接替身 consume 返回用户 id。
 */
class AiAssistantConversationControllerTest {

    private IUserService userService;
    private AiAssistantProxyManager proxyManager;
    private AiAssistantTicketManager ticketManager;
    private IAiConversationService conversationService;
    private AiAssistantController controller;

    /** 播种进 Sa-Token 内存 DAO 的真 token：loginId=100（与 USER_ID 同一账号） */
    private static final String TOKEN = "t36-conversation-satoken";

    @BeforeEach
    void setUp() {
        userService = mock(IUserService.class);
        proxyManager = mock(AiAssistantProxyManager.class);
        ticketManager = mock(AiAssistantTicketManager.class);
        conversationService = mock(IAiConversationService.class);
        controller = new AiAssistantController(userService, proxyManager, ticketManager,
                "satoken", "SESSION", conversationService, mock(AiAssistantOperationManager.class));
    }

    @AfterEach
    void clearSeededToken() {
        SaManager.getSaTokenDao().delete(StpKit.SPACE.splicingKeyTokenValue(TOKEN));
    }

    private User loginUser() {
        User user = new User();
        user.setId(100L);
        user.setUserRole("user");
        return user;
    }

    private MockHttpServletRequest loggedInRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUser(any())).thenReturn(loginUser());
        when(ticketManager.consume(anyString())).thenReturn(100L);
        // 门槛三要真 token：Sa-Token 无 Spring 上下文时默认走内存 DAO，直接播种"100 的登录态"
        // （与 AiAssistantControllerTest 的 R4 用例同一手法；login() 需要 SaTokenContext，单测里不可用）
        SaManager.getSaTokenDao().set(StpKit.SPACE.splicingKeyTokenValue(TOKEN), "100", 600L);
        request.addHeader("satoken", TOKEN);
        // spring-test 的 MockHttpServletRequest 没有 addCookie，用 setCookies（与既有 AiAssistantControllerTest 同款）
        request.setCookies(new Cookie("SESSION", "stub-session"));
        return request;
    }

    @Test
    void conversationsRequireLogin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUser(any())).thenReturn(null);

        assertThatThrownBy(() -> controller.conversations(1, 20, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void conversationsReturnVOList() {
        MockHttpServletRequest request = loggedInRequest();
        when(conversationService.listByUser(eq(100L), eq(1L), eq(20L))).thenReturn(List.of(
                AiConversationVO.builder().chatId("chat-1").title("标题")
                        .createTime(new Date(0L)).updateTime(new Date(1L)).build()));

        var response = controller.conversations(1, 20, request);

        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getChatId()).isEqualTo("chat-1");
    }

    @Test
    void chatRecordsConversationBeforeForwarding() {
        MockHttpServletRequest request = loggedInRequest();
        when(proxyManager.chat(any(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SseEmitter());

        controller.chat("你好", "chat-1", "ticket-1", request);

        verify(conversationService).recordChat(100L, "chat-1", "你好");
        Mockito.verifyNoMoreInteractions(conversationService);
    }

    @Test
    void messagesRequireLogin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUser(any())).thenReturn(null);

        assertThatThrownBy(() -> controller.conversationMessages("chat-1", request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void messagesRejectForeignConversationWithoutUpstream() {
        MockHttpServletRequest request = loggedInRequest();
        Mockito.doThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在或无权访问"))
                .when(conversationService).requireOwned(100L, "foreign-chat");

        assertThatThrownBy(() -> controller.conversationMessages("foreign-chat", request))
                .isInstanceOf(BusinessException.class);
        verify(proxyManager, never()).fetchConversationMessages(anyLong(), anyString());
    }

    @Test
    void messagesRelayEngineBodyVerbatim() {
        MockHttpServletRequest request = loggedInRequest();
        when(proxyManager.fetchConversationMessages(100L, "chat-1"))
                .thenReturn("{\"code\":0,\"data\":[]}");

        var entity = controller.conversationMessages("chat-1", request);

        assertThat(entity.getBody()).isEqualTo("{\"code\":0,\"data\":[]}");
        verify(conversationService).requireOwned(100L, "chat-1");
    }
}
