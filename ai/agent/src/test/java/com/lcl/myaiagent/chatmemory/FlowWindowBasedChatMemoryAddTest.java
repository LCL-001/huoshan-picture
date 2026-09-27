package com.lcl.myaiagent.chatmemory;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.lcl.myaiagent.config.OpenAiChatModels;
import com.lcl.myaiagent.config.OpenAiModelProperties;
import com.lcl.myaiagent.model.po.ChatMessage;
import com.lcl.myaiagent.model.po.ChatSummary;
import com.lcl.myaiagent.repository.ChatMessageRepository;
import com.lcl.myaiagent.repository.ChatSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * add() 写入契约的守护测试（2026-09-27 重写，替代旧的指纹去重守护测试）。
 * <p>
 * 写入方只有两处——{@code BaseAgent} 运行开始写用户消息、{@code ToolCallAgent.think()} 每步写助手
 * 消息，每条消息都只该被写一次，所以 add() 不再去重：同文本消息重复写入也如实落库
 * （旧去重的副作用是"用户隔轮重复同一句话，第二句进不了记忆"，advisor 移除后随指纹一并退役）。
 * system 行仍保持"全局一份"：新 system 进来先删旧的再写新的。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("会话记忆写入契约（显式读写，不去重）")
class FlowWindowBasedChatMemoryAddTest {

    private static final String CHAT_ID = "chat-1";

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatSummaryRepository chatSummaryRepository;

    private FlowWindowBasedChatMemory memory;

    /** 活的消息表：save 往里加、查询从里读 */
    private final List<ChatMessage> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 本类只测写入（add），不碰摘要：给一个"主脑未配置"的持有器就够了
        memory = new FlowWindowBasedChatMemory(chatMessageRepository, chatSummaryRepository,
                OpenAiChatModels.from(new OpenAiModelProperties()));
        store.clear();
        mockStoredMessages();
        doAnswer(invocation -> {
            store.add(invocation.getArgument(0));
            return true;
        }).when(chatMessageRepository).save(any());
    }

    @Test
    @DisplayName("同一句话重复写入也如实落库：写入方保证只写一次，add 不再代劳去重")
    void storesDuplicateUserMessagesAsIs() {
        UserMessage question = new UserMessage("帮我整理空间");

        memory.add(CHAT_ID, List.of(question));
        memory.add(CHAT_ID, List.of(question));
        memory.add(CHAT_ID, List.of(question));

        // 旧的去重实现在这里只写 1 次；显式读写后每条都落库（跨轮重复的提问必须都在历史里）
        verify(chatMessageRepository, times(3)).save(any());
    }

    @Test
    void storesDifferentMessages() {
        memory.add(CHAT_ID, List.of(new UserMessage("帮我整理空间")));
        memory.add(CHAT_ID, List.of(new UserMessage("再找几张山景的图")));

        verify(chatMessageRepository, times(2)).save(any());
    }

    @Test
    void doesNotCollapseDistinctToolDecisionRows() {
        // 两步工具调用的文本都为空，只有 toolCall id 不同：必须都写进去，否则折叠条会丢步骤
        AssistantMessage first = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "listSpaces", "{}"))).build();
        AssistantMessage second = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_2", "function", "listPictures", "{}"))).build();

        memory.add(CHAT_ID, List.of(first));
        memory.add(CHAT_ID, List.of(second));

        verify(chatMessageRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("system 全局只保留一份：新 system 进来先删旧行、再写新行")
    void replacesTheSystemPrompt() {
        // 库里已有一条同文本的 system 行：add() 会先删旧行，然后把新行写进去
        store.add(row(MessageType.SYSTEM, "你是图库助手"));
        mockUpdateChain();

        memory.add(CHAT_ID, List.of(new SystemMessage("你是图库助手")));

        verify(chatMessageRepository).lambdaUpdate();
        verify(chatMessageRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("普通消息写入不触碰 system 行")
    void keepsSystemRowsWhenWritingNormalMessages() {
        mockUpdateChain();

        memory.add(CHAT_ID, List.of(new UserMessage("帮我整理空间")));

        verify(chatMessageRepository, never()).lambdaUpdate();
        verify(chatMessageRepository, times(1)).save(any());
    }

    // ---------- 打桩小工具 ----------

    private ChatMessage row(MessageType type, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(CHAT_ID);
        message.setMessageType(type);
        message.setContent(content);
        message.setMetadata(new java.util.HashMap<>());
        return message;
    }

    @SuppressWarnings("unchecked")
    private void mockStoredMessages() {
        LambdaQueryChainWrapper<ChatMessage> query = mock(LambdaQueryChainWrapper.class);
        doReturn(query).when(query).eq(any(SFunction.class), any());
        doReturn(store).when(query).list();
        doReturn(query).when(chatMessageRepository).lambdaQuery();
    }

    @SuppressWarnings("unchecked")
    private void mockUpdateChain() {
        com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<ChatMessage> update =
                mock(com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper.class);
        doReturn(update).when(update).eq(any(SFunction.class), any());
        doReturn(update).when(chatMessageRepository).lambdaUpdate();
    }
}
