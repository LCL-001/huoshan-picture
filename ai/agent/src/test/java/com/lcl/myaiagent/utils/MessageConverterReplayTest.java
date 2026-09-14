package com.lcl.myaiagent.utils;

import com.lcl.myaiagent.model.po.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12 联调修复的守护测试：记忆回放**不得带工具调用痕迹**。
 * <p>
 * 背景（2026-09-14 真机实测）：本引擎只持久化 assistant 的 toolCall 决策行、不持久化工具响应行，
 * 所以回放出来的 assistant(tool_calls) 后面没有配对 tool 消息——OpenAI 协议要求两者必须成对，
 * DeepSeek 直接回 400（`assistant message with 'tool_calls' must be followed by tool messages`）。
 * </p>
 */
class MessageConverterReplayTest {

    @Test
    void dropsUnpairedToolPlumbingSoStrictProvidersAcceptTheReplay() {
        List<ChatMessage> rows = List.of(
                row(MessageType.USER, "把空间整理一下", null),
                row(MessageType.ASSISTANT, "我先列出你的空间。", toolCalls("call_1", "listSpaces")),
                row(MessageType.ASSISTANT, "", toolCalls("call_2", "listPictures")),
                row(MessageType.TOOL, "{\"total\":1}", toolResponses("call_1", "listSpaces")),
                row(MessageType.ASSISTANT, "整理完成。", null));

        List<Message> replay = MessageConverter.toReplayMessages(rows);

        assertThat(replay).extracting(Message::getText)
                .as("纯决策行（空文本）与工具响应行都不给模型看")
                .containsExactly("把空间整理一下", "我先列出你的空间。", "整理完成。");
        assertThat(replay).noneMatch(ToolResponseMessage.class::isInstance);
        assertThat(((AssistantMessage) replay.get(1)).getToolCalls())
                .as("回放里的 assistant 不得再带 tool_calls：库里没有配对工具响应，带上就是非法序列")
                .isEmpty();
    }

    @Test
    void keepsUserAndSystemRowsIntact() {
        List<ChatMessage> rows = List.of(
                row(MessageType.SYSTEM, "你是图库助手", null),
                row(MessageType.USER, "我有几个空间", null));

        List<Message> replay = MessageConverter.toReplayMessages(rows);

        assertThat(replay).hasSize(2);
        assertThat(replay.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(replay.get(1).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(replay.get(1).getText()).isEqualTo("我有几个空间");
    }

    private static ChatMessage row(MessageType type, String content, Map<String, Object> metadata) {
        ChatMessage message = new ChatMessage();
        message.setConversationId("test-conversation");
        message.setMessageType(type);
        message.setContent(content);
        message.setMetadata(metadata == null ? new HashMap<>() : metadata);
        return message;
    }

    private static Map<String, Object> toolCalls(String id, String name) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("toolCalls", List.of(Map.of(
                "id", id, "type", "function", "name", name, "arguments", "{}")));
        return metadata;
    }

    private static Map<String, Object> toolResponses(String id, String name) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("toolResponses", List.of(Map.of(
                "id", id, "name", name, "responseData", "{\"total\":1}")));
        return metadata;
    }
}
