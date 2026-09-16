package com.lcl.myaiagent.service;

import com.lcl.myaiagent.config.OpenAiChatModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationTitleServiceTest {

    private static final String CHAT_ID = "chat-1";
    private static final String USER_ID = "u-1";

    @Mock
    private OpenAiChatModels openAiChatModels;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ChatModel chatModel;

    @Test
    void shouldNormalizeAndLimitGeneratedTitle() {
        assertEquals("Spring 事务失效排查", ConversationTitleService.normalizeTitle("\n“Spring 事务失效排查”\n"));
        assertEquals("Java 面试事务与索引问题及更多内", ConversationTitleService.normalizeTitle("Java 面试事务与索引问题及更多内容"));
        assertNull(ConversationTitleService.normalizeTitle("   "));
    }

    /** 2026-09-16：标题与对话同源走主脑，不再注入容器默认 ChatModel */
    @Test
    void generatesTitleWithAssistantModel() {
        when(openAiChatModels.hasAssistant()).thenReturn(true);
        when(openAiChatModels.assistant()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("“Spring 事务失效排查”")))));

        service().generateForFirstMessage(CHAT_ID, USER_ID, "事务为什么不回滚");

        // 模型原样吐出的引号在落库前被剥掉（normalizeTitle）
        verify(conversationService).updateGeneratedTitleIfDefault(CHAT_ID, USER_ID, "Spring 事务失效排查");
    }

    /** 主脑没配就留默认标题：标题是锦上添花，不该为一个辅助功能抛错 */
    @Test
    void skipsGenerationWhenAssistantIsNotConfigured() {
        when(openAiChatModels.hasAssistant()).thenReturn(false);

        service().generateForFirstMessage(CHAT_ID, USER_ID, "事务为什么不回滚");

        verifyNoInteractions(chatModel);
        verify(conversationService, never()).updateGeneratedTitleIfDefault(any(), any(), any());
    }

    private ConversationTitleService service() {
        return new ConversationTitleService(openAiChatModels, conversationService);
    }
}
