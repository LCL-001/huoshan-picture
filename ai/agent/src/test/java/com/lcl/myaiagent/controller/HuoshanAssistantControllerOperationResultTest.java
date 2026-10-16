package com.lcl.myaiagent.controller;

import com.lcl.myaiagent.agent.HuoshanAssistantSession;
import com.lcl.myaiagent.common.BaseResponse;
import com.lcl.myaiagent.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T44 引擎"确认执行结果"记录端点单测：匿名子类覆写写缝隙 {@code appendConversationMessage} 绕开
 * FlowWindowBasedChatMemory（需真实仓储），直接调方法，不启 Spring、不依赖 MySQL，进门禁。
 */
class HuoshanAssistantControllerOperationResultTest {

    private String capturedConversationId;
    private Message capturedMessage;

    private HuoshanAssistantController controller() {
        return new HuoshanAssistantController() {
            @Override
            protected void appendConversationMessage(String conversationId, Message message) {
                capturedConversationId = conversationId;
                capturedMessage = message;
            }
        };
    }

    private HuoshanAssistantController.OperationResultNoteRequest note(String content) {
        HuoshanAssistantController.OperationResultNoteRequest request =
                new HuoshanAssistantController.OperationResultNoteRequest();
        request.setContent(content);
        return request;
    }

    @Test
    void appendsPrefixedUserFactToDeterministicConversation() {
        HuoshanAssistantController controller = controller();

        BaseResponse<Boolean> response = controller.recordOperationResult(
                "chat-1", "100", note("用户已确认并执行批量编辑：提交 2 张。"));

        assertThat(response.getData()).isTrue();
        assertThat(capturedConversationId).isEqualTo(HuoshanAssistantSession.conversationId(
                HuoshanAssistantSession.owner("100"), "chat-1"));
        // 以 USER 角色承载：模型回放可见、前端历史按来源标记过滤（见 ChatHistoryAssembler）
        assertThat(capturedMessage.getMessageType()).isEqualTo(MessageType.USER);
        assertThat(capturedMessage.getText())
                .isEqualTo(HuoshanAssistantSession.OPERATION_RESULT_PREFIX + "用户已确认并执行批量编辑：提交 2 张。");
    }

    /** T59：可信判据是持久化来源标记（system_fact），而不是可被用户伪造的文本前缀。 */
    @Test
    void marksRecordedFactAsSystemFact() {
        HuoshanAssistantController controller = controller();

        controller.recordOperationResult("chat-1", "100", note("用户已确认并执行批量编辑：提交 2 张。"));

        assertThat(capturedMessage.getMetadata())
                .containsEntry(HuoshanAssistantSession.METADATA_SOURCE_KEY,
                        HuoshanAssistantSession.SOURCE_SYSTEM_FACT);
    }

    @Test
    void blankChatIdIsRejected() {
        assertThatThrownBy(() -> controller().recordOperationResult(" ", "100", note("已执行")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void blankContentIsRejected() {
        assertThatThrownBy(() -> controller().recordOperationResult("chat-1", "100", note("  ")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void illegalUserIdIsRejected() {
        assertThatThrownBy(() -> controller().recordOperationResult("chat-1", "not-a-number", note("已执行")))
                .isInstanceOf(BusinessException.class);
    }
}
