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
import org.springframework.ai.chat.model.ChatModel;

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
 * 会话记忆"只写一次"的守护测试（2026-09-14 T12 后续修复）。
 * <p>
 * 复现来源：Spring AI 的 {@code MessageChatMemoryAdvisor} 在每次模型调用前都会
 * {@code add(prompt.getUserMessage())}，而它取的是"请求里最后一条用户消息"；本引擎的循环每轮重新提交
 * 整段 messageList，最后一条用户消息长期不变 ⇒ 同一条用户消息被反复入库（实测 3 次）。
 * 记忆层按指纹回绝重复写入，这里把行为钉住。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlowWindowBasedChatMemoryDedupeTest {

    private static final String CHAT_ID = "chat-1";

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatSummaryRepository chatSummaryRepository;

    private FlowWindowBasedChatMemory memory;

    /** 活的消息表：save 往里加、查询从里读——否则测不出"第二次 add 时库里已经有这条了" */
    private final List<ChatMessage> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        memory = new FlowWindowBasedChatMemory(chatMessageRepository, chatSummaryRepository,
                OpenAiChatModels.from(new OpenAiModelProperties()), chatModel);
        store.clear();
        mockStoredMessages();
        doAnswer(invocation -> {
            store.add(invocation.getArgument(0));
            return true;
        }).when(chatMessageRepository).save(any());
    }

    @Test
    void writesTheSameUserMessageOnlyOnce() {
        UserMessage question = new UserMessage("帮我整理空间");

        memory.add(CHAT_ID, List.of(question));
        memory.add(CHAT_ID, List.of(question));
        memory.add(CHAT_ID, List.of(question));

        verify(chatMessageRepository, times(1)).save(any());
    }

    @Test
    void skipsMessagesThatAreAlreadyInTheStore() {
        store.add(row(MessageType.USER, "帮我整理空间"));

        memory.add(CHAT_ID, List.of(new UserMessage("帮我整理空间")));

        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void keepsDifferentMessages() {
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
    void stillWritesTheSystemPromptAfterDeletingTheOldOne() {
        // 库里已有一条同文本的 system 行：add() 会先删旧行，然后必须把新行写进去（不能被指纹挡住）
        store.add(row(MessageType.SYSTEM, "你是图库助手"));
        mockUpdateChain();

        memory.add(CHAT_ID, List.of(new SystemMessage("你是图库助手")));

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
