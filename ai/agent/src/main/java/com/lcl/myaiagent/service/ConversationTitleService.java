package com.lcl.myaiagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationTitleService {

    private final ChatModel chatModel;
    private final ConversationService conversationService;

    @Async
    public void generateForFirstMessage(String conversationId, String userId, String firstMessage) {
        if (conversationId == null || userId == null || firstMessage == null || firstMessage.isBlank()) {
            return;
        }
        try {
            String title = chatModel.call(new Prompt("""
                    请为下面的用户问题生成一个简洁的中文会话标题。
                    只输出标题本身，不要引号、序号、解释或标点；不超过 18 个字符。
                    用户问题：%s
                    """.formatted(firstMessage))).getResult().getOutput().getText();
            String normalizedTitle = normalizeTitle(title);
            if (normalizedTitle != null) {
                conversationService.updateGeneratedTitleIfDefault(conversationId, userId, normalizedTitle);
            }
        } catch (Exception exception) {
            log.warn("Failed to generate conversation title for {}", conversationId, exception);
        }
    }

    static String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String normalized = title.replaceAll("[\\r\\n]+", " ").replaceAll("^[\\s\"'“”]+|[\\s\"'“”]+$", "").trim();
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.substring(0, Math.min(normalized.length(), 18));
    }
}
