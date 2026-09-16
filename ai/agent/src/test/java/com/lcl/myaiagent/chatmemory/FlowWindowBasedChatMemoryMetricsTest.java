package com.lcl.myaiagent.chatmemory;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.lcl.myaiagent.config.OpenAiChatModels;
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
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * T3：水位线复用率测量（非功能测试，产出可引用的数字）。
 *
 * 模拟一次 20 步的 Agent 执行：每步先落一条用户消息、读一次记忆（对应
 * Memory Advisor 的步前读取），再落一条助手消息（对应步后写回）。
 * 用 Mockito 统计摘要模型 call 的真实次数，得出：
 *   读取总次数 / 摘要调用次数 / 复用命中次数 / 命中率，
 * 并与"无水位线基线"（每次超预算读取都重新摘要）做对比。
 *
 * 数据为固定尺寸（非随机），结果确定可复现；尺寸按 token 估算规则
 * （字符数 × 3 ÷ 2）反推，使预算在第十步左右被首次越过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("水位线复用率测量")
class FlowWindowBasedChatMemoryMetricsTest {

    private static final String CHAT_ID = "metrics-chat";
    private static final int STEPS = 20;
    private static final int USER_CHARS = 150;      // 225 token
    private static final int ASSISTANT_CHARS = 250; // 375 token，每步合计 600 token
    private static final int SUMMARY_CHARS = 80;    // mock 摘要固定 80 字符

    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder COMPRESS_INPUT_CHARS = new LongAdder();

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatSummaryRepository chatSummaryRepository;

    /** 摘要走主脑（OpenAiChatModels.assistant()）：这里把主脑指到下面的 chatModel 桩上 */
    @Mock
    private OpenAiChatModels openAiChatModels;

    private FlowWindowBasedChatMemory memory;
    private List<ChatMessage> liveStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CALLS.reset();
        COMPRESS_INPUT_CHARS.reset();
        when(openAiChatModels.assistant()).thenReturn(chatModel);
        memory = new FlowWindowBasedChatMemory(chatMessageRepository, chatSummaryRepository, openAiChatModels);

        // 有状态消息仓库：list() 返回活列表引用，步间追加对下一次读取可见
        liveStore = new ArrayList<>();
        ChatMessage system = new ChatMessage();
        system.setId(1L);
        system.setConversationId(CHAT_ID);
        system.setMessageType(MessageType.SYSTEM);
        system.setContent("s".repeat(200)); // 300 token
        liveStore.add(system);

        AtomicInteger nextId = new AtomicInteger(2);
        LambdaQueryChainWrapper<ChatMessage> mq = mock(LambdaQueryChainWrapper.class);
        doReturn(mq).when(mq).eq(any(SFunction.class), any());
        doReturn(mq).when(mq).orderByAsc(any(SFunction.class));
        doReturn(liveStore).when(mq).list();
        doReturn(mq).when(chatMessageRepository).lambdaQuery();

        // 有状态摘要仓库：one() 返回最新摘要对象，saveOrUpdate 把最新对象放回
        AtomicReference<ChatSummary> summaryStore = new AtomicReference<>();
        LambdaQueryChainWrapper<ChatSummary> sq = mock(LambdaQueryChainWrapper.class);
        doReturn(sq).when(sq).eq(any(SFunction.class), any());
        doAnswer(inv -> summaryStore.get()).when(sq).one();
        doReturn(sq).when(chatSummaryRepository).lambdaQuery();
        when(chatSummaryRepository.saveOrUpdate(any(ChatSummary.class))).thenAnswer(inv -> {
            summaryStore.set(inv.getArgument(0));
            return true;
        });

        // 摘要模型桩：固定返回短摘要，记录每次调用的输入长度
        when(chatModel.call(anyString())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            COMPRESS_INPUT_CHARS.add((long) prompt.length());
            CALLS.increment();
            return "模拟摘要。".repeat(SUMMARY_CHARS / 5);
        });
    }

    @Test
    @DisplayName("预算 4096：20 步执行，摘要调用远少于读取次数")
    void measureReuseRateWithDefaultBudget() {
        Measurement m = simulate(4096);
        print("默认预算 4096", m);

        assertTrue(m.calls() >= 1, "预算被越过，至少应发生一次压缩");
        assertTrue(m.hitRate() >= 60.0, "复用命中率应不低于 60%，实际=" + m.hitRate() + "%");
        assertTrue(m.calls() <= m.reads() / 2, "调用应远少于读取");
    }

    @Test
    @DisplayName("预算 8192：余量变大，复用率显著上升（预算-复用率权衡）")
    void measureReuseRateWithLargerBudget() {
        Measurement m = simulate(8192);
        print("加倍预算 8192", m);

        assertTrue(m.hitRate() >= 80.0, "加倍预算后命中率应显著上升，实际=" + m.hitRate() + "%");
    }

    // ---------- 模拟器 ----------

    private Measurement simulate(int budget) {
        CALLS.reset();
        COMPRESS_INPUT_CHARS.reset();
        AtomicInteger nextId = new AtomicInteger(2);
        int reads = 0;
        int firstCompressRead = -1;

        for (int step = 1; step <= STEPS; step++) {
            liveStore.add(msg(nextId.getAndIncrement(), "u".repeat(USER_CHARS)));
            int before = CALLS.intValue();
            memory.compress(CHAT_ID, budget);
            reads++;
            int after = CALLS.intValue();
            if (after > before && firstCompressRead < 0) {
                firstCompressRead = reads;
            }
            liveStore.add(msg(nextId.getAndIncrement(), "a".repeat(ASSISTANT_CHARS)));
        }

        int calls = CALLS.intValue();
        int reuses = reads - calls;
        int baseline = firstCompressRead > 0 ? STEPS - firstCompressRead + 1 : 0;
        return new Measurement(reads, calls, baseline, COMPRESS_INPUT_CHARS.sum());
    }

    private void print(String scenario, Measurement m) {
        System.out.printf("""
                        %n==== 水位线复用率测量（%s，%d 步模拟） ====
                        读取次数       : %d
                        摘要模型调用   : %d（复用命中 %d 次，命中率 %.0f%%）
                        无水位线基线   : 约 %d 次（每次超预算读取都重新摘要）
                        节省模型调用   : %d 次
                        摘要输入总字符 : %d
                        """,
                scenario, STEPS, m.reads(), m.calls(), m.reuses(), m.hitRate(),
                m.baselineNoWatermark(), m.baselineNoWatermark() - m.calls(),
                m.compressInputChars());
    }

    private record Measurement(int reads, int calls, int baselineNoWatermark, long compressInputChars) {
        int reuses() {
            return reads - calls;
        }

        double hitRate() {
            return 100.0 * (reads - calls) / reads;
        }
    }

    private ChatMessage msg(long id, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setConversationId(CHAT_ID);
        m.setMessageType(MessageType.USER);
        m.setContent(content);
        return m;
    }
}
