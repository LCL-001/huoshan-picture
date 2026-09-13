package com.lcl.myaiagent.chatmemory;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.lcl.myaiagent.model.po.ChatMessage;
import com.lcl.myaiagent.model.po.ChatSummary;
import com.lcl.myaiagent.repository.ChatMessageRepository;
import com.lcl.myaiagent.repository.ChatSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * FlowWindowBasedChatMemory 的单元测试。
 * 不连数据库、不调真实模型：仓储和 ChatModel 全部用 Mockito 桩替，
 * 只验证 compress 的分支逻辑（压缩/复用/降级/硬裁剪）本身是否正确。
 *
 * token 估算规则：字符数 × 3 ÷ 2。构造数据时按这个规则反推字符数。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlowWindowBasedChatMemoryTest {

    private static final String CHAT_ID = "chat-1";
    private static final int BUDGET = 4096; // 与 DEFAULT_MAX_TOKEN 一致

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatSummaryRepository chatSummaryRepository;

    private FlowWindowBasedChatMemory memory;

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor 的字段声明顺序：chatMessageRepository, chatModel, chatSummaryRepository
        memory = new FlowWindowBasedChatMemory(chatMessageRepository, chatModel, chatSummaryRepository);
    }

    // ---------- 构造数据的小工具 ----------

    /** 造一条 ChatMessage。字符数按 1 字符 ≈ 1.5 token 折算 token */
    private ChatMessage po(long id, MessageType type, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setConversationId(CHAT_ID);
        m.setMessageType(type);
        m.setContent(content);
        return m;
    }

    /** 一条 content 为 nChars 个字符的普通消息，估算 token = nChars * 3 / 2 */
    private ChatMessage normal(long id, int nChars) {
        return po(id, MessageType.USER, "x".repeat(nChars));
    }

    /**
     * 给 chatMessageRepository 的 lambdaQuery 链打桩：
     * eq(...)/orderByAsc(...) 返回自身，list() 返回给定的 history
     */
    @SuppressWarnings("unchecked")
    private void mockMessageQuery(List<ChatMessage> history) {
        LambdaQueryChainWrapper<ChatMessage> q = mock(LambdaQueryChainWrapper.class);
        doReturn(q).when(q).eq(any(SFunction.class), any());
        doReturn(q).when(q).orderByAsc(any(SFunction.class));
        doReturn(history).when(q).list();
        doReturn(q).when(chatMessageRepository).lambdaQuery();
    }

    /** 给 chatSummaryRepository 的 lambdaQuery 链打桩，one() 返回 existing（可为 null） */
    @SuppressWarnings("unchecked")
    private void mockSummaryQuery(ChatSummary existing) {
        LambdaQueryChainWrapper<ChatSummary> q = mock(LambdaQueryChainWrapper.class);
        doReturn(q).when(q).eq(any(SFunction.class), any());
        doReturn(existing).when(q).one();
        doReturn(q).when(chatSummaryRepository).lambdaQuery();
    }

    /** 给 ChatModel 打桩：call(prompt) 返回给定摘要文本 */
    private void mockModelSummary(String summaryText) {
        when(chatModel.call(anyString())).thenReturn(summaryText);
    }

    // ---------- 场景一：正常压缩 ----------

    @Test
    @DisplayName("历史超预算且无可复用摘要：老段被压缩，返回 system + 摘要 + 最近N条，水位线落库")
    void compress_normalPath() {
        // 1 条 system(100字符=150 token) + 12 条普通消息(每条300字符=450 token)
        // 全量 5550 > 4096 触发压缩；老段 = 前 2 条(id 101,102)，最近段 = 后 10 条
        List<ChatMessage> history = new ArrayList<>();
        history.add(po(100, MessageType.SYSTEM, "s".repeat(100)));
        for (long i = 101; i <= 112; i++) {
            history.add(normal(i, 300));
        }
        mockMessageQuery(history);
        mockSummaryQuery(null);                       // 库里没有旧摘要 → 必须调模型
        mockModelSummary("这是压缩出的摘要");

        List<Message> result = memory.get(CHAT_ID);

        // 返回结构：1 system + 1 摘要 + 10 条最近 = 12 条
        assertEquals(12, result.size());
        assertInstanceOf(SystemMessage.class, result.get(0));           // 人设在最前
        assertInstanceOf(AssistantMessage.class, result.get(1));        // 摘要用 AssistantMessage 承载
        assertTrue(result.get(1).getText().contains("这是压缩出的摘要"));
        // 最近 10 条原文按原顺序在尾部（id 103~112），抽查首尾两条的内容长度
        assertTrue(result.get(2).getText().length() == 300);            // 最近段第一条是 id=103 的原文
        assertTrue(result.get(11).getText().length() == 300);           // 最近段最后一条是 id=112 的原文

        // 摘要落库：chat_id 正确，水位线 = 老段最后一条（id=102）
        ArgumentCaptor<ChatSummary> captor = ArgumentCaptor.forClass(ChatSummary.class);
        verify(chatSummaryRepository).saveOrUpdate(captor.capture());
        assertEquals(CHAT_ID, captor.getValue().getChatId());
        assertEquals(102L, captor.getValue().getLastMessageId());
    }

    // ---------- 场景二：水位线命中，复用摘要不调模型 ----------

    @Test
    @DisplayName("摘要+水位线后新消息未超预算：直接复用摘要，不调模型、不重复写库")
    void compress_reuseSummary() {
        // system(1000字符=1500 token) + 12 条普通(每条200字符=300 token)
        // 全量 5100 > 4096 触发压缩；已有摘要水位线=102 → 新增=10 条(3000 token)
        // 摘要(约15) + 新增(3000) <= 预算 → 命中复用路径，零模型调用
        List<ChatMessage> history = new ArrayList<>();
        history.add(po(100, MessageType.SYSTEM, "s".repeat(1000)));
        for (long i = 101; i <= 112; i++) {
            history.add(normal(i, 200));
        }
        mockMessageQuery(history);
        // 旧摘要的水位线 = 102，之后有 10 条新消息但总量不超预算
        ChatSummary existing = new ChatSummary();
        existing.setChatId(CHAT_ID);
        existing.setSummary("这是上次的摘要");
        existing.setLastMessageId(102L);
        mockSummaryQuery(existing);

        List<Message> result = memory.get(CHAT_ID);

        assertEquals(12, result.size());   // 1 system + 1 摘要 + 10 新增
        assertTrue(result.get(1).getText().contains("这是上次的摘要"));
        verify(chatModel, never()).call(anyString());               // 模型一次都没调
        verify(chatSummaryRepository, never()).saveOrUpdate(any()); // 也没有重复写库
    }

    // ---------- 场景三：模型调用失败，降级为硬裁剪 ----------

    @Test
    @DisplayName("模型调用抛异常：降级为 trimByTokens 硬裁剪，不写摘要表")
    void compress_degradeOnModelFailure() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(po(100, MessageType.SYSTEM, "s".repeat(100)));
        for (long i = 101; i <= 112; i++) {
            history.add(normal(i, 300));
        }
        mockMessageQuery(history);
        mockSummaryQuery(null);
        when(chatModel.call(anyString())).thenThrow(new RuntimeException("模拟模型挂了"));

        List<Message> result = memory.get(CHAT_ID);

        // 降级结果非空，第一条是 system，且总 token 没超预算（system 不计入裁剪预算）
        assertFalse(result.isEmpty());
        assertInstanceOf(SystemMessage.class, result.get(0));
        int normalTokens = result.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .mapToInt(m -> m.getText() == null ? 0 : m.getText().length() * 3 / 2)
                .sum();
        assertTrue(normalTokens <= BUDGET, "降级后普通消息总 token 应不超过预算，实际=" + normalTokens);
        verify(chatSummaryRepository, never()).saveOrUpdate(any()); // 失败不落摘要
    }

    // ---------- 场景四：历史不足 N 条，只裁不压 ----------

    @Test
    @DisplayName("历史不足N条：不触发压缩，走硬裁剪，全程不调模型")
    void compress_tooShortToCompress() {
        // 1 system(150) + 5 条普通(每条700字符=1050 token)，全量 5400 > 4096，
        // 但普通消息只有 5 条 ≤ RECENT_KEEP(10) → 走 trimByTokens
        List<ChatMessage> history = new ArrayList<>();
        history.add(po(100, MessageType.SYSTEM, "s".repeat(100)));
        for (long i = 101; i <= 105; i++) {
            history.add(normal(i, 700));
        }
        mockMessageQuery(history);
        mockSummaryQuery(null);

        List<Message> result = memory.get(CHAT_ID);

        // 硬裁剪：从尾往前保留 3 条(3150)，第 4 条就超预算 → 1 system + 3 normal
        assertEquals(4, result.size());
        assertInstanceOf(SystemMessage.class, result.get(0));
        verify(chatModel, never()).call(anyString());               // 不该有模型调用
        verify(chatSummaryRepository, never()).saveOrUpdate(any());
    }

    // ---------- 场景五：未超预算，原样返回 ----------

    @Test
    @DisplayName("全量在预算内：原样返回，不压缩不裁剪")
    void get_withinBudget_returnsFullHistory() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(po(100, MessageType.SYSTEM, "s".repeat(100)));
        for (long i = 101; i <= 105; i++) {
            history.add(normal(i, 100)); // 每条150 token，总共 900，远低于预算
        }
        mockMessageQuery(history);
        mockSummaryQuery(null);

        List<Message> result = memory.get(CHAT_ID);

        assertEquals(6, result.size());              // 原样：1 system + 5 normal
        verify(chatModel, never()).call(anyString());
        verify(chatSummaryRepository, never()).saveOrUpdate(any());
    }
}
