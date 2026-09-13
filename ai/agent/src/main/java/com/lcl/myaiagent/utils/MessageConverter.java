package com.lcl.myaiagent.utils;

import com.lcl.myaiagent.model.po.ChatMessage;
import org.springframework.ai.chat.messages.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息转换器工具类
 * 用于在Spring AI的Message对象和内部ChatMessage实体之间进行双向转换
 */
public class MessageConverter {

    private static final String TOOL_RESPONSES_KEY = "toolResponses";
    private static final String TOOL_CALLS_KEY = "toolCalls";

    /**
     * 将Spring AI的Message对象转换为内部ChatMessage实体
     * 提取消息类型、内容、元数据等信息，并关联到指定会话。
     * 对 ToolResponseMessage 会将其 responses 序列化到 metadata 中。
     *
     * @param message        Spring AI的消息对象，包含消息内容和元数据
     * @param conversationId 会话唯一标识，用于关联聊天记录
     * @return ChatMessage 转换后的内部聊天消息实体
     */
    public static ChatMessage toChatMessage(Message message, String conversationId) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setConversationId(conversationId);
        chatMessage.setMessageType(message.getMessageType());
        // content 列为 NOT NULL，工具调用类消息的文本可能为 null，这里统一兜底
        chatMessage.setContent(message.getText() != null ? message.getText() : "");

        Map<String, Object> metadata = new HashMap<>(message.getMetadata());
        // 将 AssistantMessage.toolCalls 序列化到 metadata，否则历史回放后 tool 响应失去前导的 tool_calls，下一轮请求会被模型拒绝
        if (message instanceof AssistantMessage assistantMsg) {
            List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                List<Map<String, String>> serialized = toolCalls.stream()
                        .map(tc -> {
                            Map<String, String> m = new HashMap<>();
                            m.put("id", tc.id());
                            m.put("type", tc.type());
                            m.put("name", tc.name());
                            m.put("arguments", tc.arguments());
                            return m;
                        })
                        .toList();
                metadata.put(TOOL_CALLS_KEY, serialized);
            }
        }
        // 将 ToolResponseMessage.responses 序列化到 metadata
        if (message instanceof ToolResponseMessage toolMsg) {
            List<ToolResponseMessage.ToolResponse> responses = toolMsg.getResponses();
            if (responses != null && !responses.isEmpty()) {
                List<Map<String, String>> serialized = responses.stream()
                        .map(r -> {
                            Map<String, String> m = new HashMap<>();
                            m.put("id", r.id());
                            m.put("name", r.name());
                            m.put("responseData", r.responseData());
                            return m;
                        })
                        .toList();
                metadata.put(TOOL_RESPONSES_KEY, serialized);
            }
        }
        chatMessage.setMetadata(metadata);
        return chatMessage;
    }

    /**
     * 将内部ChatMessage实体转换为Spring AI的Message对象
     * 根据消息类型（用户/助手/系统/工具）创建对应的Spring AI消息实例。
     * 对 TOOL 类型会从 metadata 中恢复 ToolResponseMessage.responses。
     *
     * @param chatMessage 内部聊天消息实体，包含消息类型、内容和元数据
     * @return Message 转换后的Spring AI消息对象，具体类型取决于消息类型
     */
    public static Message toMessage(ChatMessage chatMessage) {
        MessageType messageType = chatMessage.getMessageType();
        String text = chatMessage.getContent();
        Map<String, Object> metadata = chatMessage.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        }

        return switch (messageType) {
            case USER -> new UserMessage(text);
            case ASSISTANT -> {
                AssistantMessage.Builder builder = AssistantMessage.builder()
                        .content(text)
                        .properties(metadata);
                List<AssistantMessage.ToolCall> toolCalls = deserializeToolCalls(metadata);
                if (!toolCalls.isEmpty()) {
                    builder.toolCalls(toolCalls);
                }
                yield builder.build();
            }
            case SYSTEM -> new SystemMessage(text);
            case TOOL -> {
                List<ToolResponseMessage.ToolResponse> responses = deserializeToolResponses(metadata);
                yield ToolResponseMessage.builder().metadata(metadata).responses(responses).build();
            }
        };
    }

    /**
     * 从 metadata 中反序列化 ToolCall 列表
     */
    @SuppressWarnings("unchecked")
    private static List<AssistantMessage.ToolCall> deserializeToolCalls(Map<String, Object> metadata) {
        Object raw = metadata.get(TOOL_CALLS_KEY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                result.add(new AssistantMessage.ToolCall(
                        (String) m.get("id"),
                        (String) m.get("type"),
                        (String) m.get("name"),
                        (String) m.get("arguments")
                ));
            }
        }
        return result.isEmpty() ? List.of() : result;
    }

    /**
     * 从 metadata 中反序列化 ToolResponse 列表
     */
    @SuppressWarnings("unchecked")
    private static List<ToolResponseMessage.ToolResponse> deserializeToolResponses(Map<String, Object> metadata) {
        Object raw = metadata.get(TOOL_RESPONSES_KEY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<ToolResponseMessage.ToolResponse> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                result.add(new ToolResponseMessage.ToolResponse(
                        (String) m.get("id"),
                        (String) m.get("name"),
                        (String) m.get("responseData")
                ));
            }
        }
        return result.isEmpty() ? List.of() : result;
    }

}
