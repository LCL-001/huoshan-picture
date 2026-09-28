package com.lcl.myaiagent.controller;

import com.lcl.myaiagent.common.BaseResponse;
import com.lcl.myaiagent.exception.BusinessException;
import com.lcl.myaiagent.model.po.ChatMessage;
import com.lcl.myaiagent.model.vo.ChatMessageVO;
import com.lcl.myaiagent.agent.HuoshanAssistantSession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T37 引擎只读历史端点单测：匿名子类覆写 {@code listRows} 绕开 ChatMessageService 的
 * lambdaQuery 链（难以 mock），直接调方法，不启 Spring、不依赖 MySQL，进门禁。
 */
class HuoshanAssistantControllerMessagesTest {

    private final HuoshanAssistantController controller = new HuoshanAssistantController() {
        @Override
        protected List<ChatMessage> listRows(String conversationId) {
            captured = conversationId;
            ChatMessage user = new ChatMessage();
            user.setId(1L);
            user.setMessageType(MessageType.USER);
            user.setContent("列出我的空间");
            ChatMessage assistant = new ChatMessage();
            assistant.setId(2L);
            assistant.setMessageType(MessageType.ASSISTANT);
            assistant.setContent("好的，共 3 个空间");
            return List.of(user, assistant);
        }
    };

    private String captured;

    @Test
    void blankChatIdIsRejected() {
        assertThatThrownBy(() -> controller.conversationMessages(" ", "100"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void illegalUserIdIsRejected() {
        assertThatThrownBy(() -> controller.conversationMessages("chat-1", "not-a-number"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void derivesDeterministicConversationIdAndAssemblesTurns() {
        BaseResponse<List<ChatMessageVO>> response = controller.conversationMessages("chat-1", "100");

        String expected = HuoshanAssistantSession.conversationId(
                HuoshanAssistantSession.owner("100"), "chat-1");
        assertThat(captured).isEqualTo(expected);
        assertThat(response.getData()).hasSize(2);
        assertThat(response.getData().get(0).getRole()).isEqualTo("user");
        assertThat(response.getData().get(0).getContent()).isEqualTo("列出我的空间");
        assertThat(response.getData().get(1).getRole()).isEqualTo("assistant");
        assertThat(response.getData().get(1).getContent()).isEqualTo("好的，共 3 个空间");
    }

    @Test
    void unknownConversationYieldsEmptyList() {
        HuoshanAssistantController empty = new HuoshanAssistantController() {
            @Override
            protected List<ChatMessage> listRows(String conversationId) {
                return List.of();
            }
        };
        BaseResponse<List<ChatMessageVO>> response = empty.conversationMessages("chat-none", "100");
        assertThat(response.getData()).isEmpty();
    }
}
