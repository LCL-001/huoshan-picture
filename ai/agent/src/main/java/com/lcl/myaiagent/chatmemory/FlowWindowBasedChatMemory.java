package com.lcl.myaiagent.chatmemory;

import com.lcl.myaiagent.model.po.ChatMessage;
import com.lcl.myaiagent.model.po.ChatSummary;
import com.lcl.myaiagent.repository.ChatMessageRepository;
import com.lcl.myaiagent.repository.ChatSummaryRepository;
import com.lcl.myaiagent.utils.MessageConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于数据库持久化的对话记忆实现
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlowWindowBasedChatMemory implements ChatMemory {
    private static final int DEFAULT_MAX_TOKEN = 4096;// 每个对话窗口的默认最大token数

    private final ChatMessageRepository chatMessageRepository;

    // 容器里只有 ChatModel 这个 Bean（LoveApp 的 ChatClient 是方法内手工构建的局部对象，不受 Spring 管理）
    private final ChatModel chatModel;

    private final ChatSummaryRepository chatSummaryRepository;


    @Override
    public void add(String conversationId, List<Message> messages) {
        for (Message message : messages) {
            // 系统提示词全局只保留一份：新增 system 前先删旧的（对应官方"换人设清旧提示词"的逻辑）
            if (message instanceof SystemMessage) {
                chatMessageRepository.lambdaUpdate()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .eq(ChatMessage::getMessageType, MessageType.SYSTEM)
                        .remove();
            }
            chatMessageRepository.save(MessageConverter.toChatMessage(message, conversationId));          // 全量入库：这里绝不裁剪
        }
    }

    @NotNull
    @Override
    public List<Message> get(String conversationId) {
        // 外面拿到的永远是"压缩后的视图"；库里躺着的是全量——读时裁剪的落地就在这一行
        return compress(conversationId, DEFAULT_MAX_TOKEN);
    }

    @Override
    public void clear(String conversationId) {
        chatMessageRepository.lambdaUpdate()
                .eq(ChatMessage::getConversationId, conversationId)
                .remove();
        chatSummaryRepository.lambdaUpdate()             // 摘要也是会话数据，一起清
                .eq(ChatSummary::getChatId, conversationId)
                .remove();
    }
    /**
     * 最近 N 条原文不参与压缩，N=10 ≈ 最近 5 轮问答。
     * 依据：官方 MessageWindowChatMemory 默认窗口 20 条；N 与 budget 联动，
     * 摘要 + 最近 N 条要放得进预算；N 是配置，库里有全量，改值可逆。
     */
    private static final int RECENT_KEEP = 10;


    /**
     * 读取会话记忆（水位线增量摘要压缩）。
     *
     * 设计要点：
     * 1. 库里存全量，读取时裁剪——旧消息永远在库，摘要压缩才有原料，策略可随时调整；
     * 2. 触发条件是估算 token 超预算（官方按条数裁剪不管 token，这是本实现的动机）；
     * 3. 摘要覆盖到哪条消息由水位线（lastMessageId）标记：只要"摘要 + 水位线之后的新消息"
     *    没超预算就零模型调用直接返回；超预算才做一次增量压缩（旧摘要作为上下文合并新消息），
     *    而不是整段重压——多步 Agent 每步读记忆时，绝大多数步骤都命中零调用路径；
     * 4. 模型调用失败降级为 trimByTokens 硬裁剪——压缩是优化，保底可用性优先。
     */
    public List<Message> compress(String conversationId, int budget) {
        // 按 id 正序（老→新）。ASSIGN_ID 雪花 id 趋势递增，用它排序比 createTime 稳：
        // createTime 精度秒级，同一秒多条消息的顺序会抖动，压缩边界可能切错
        List<ChatMessage> history = chatMessageRepository.lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getId)
                .list();
        if (history.isEmpty()) {
            return Collections.emptyList();
        }

        // 触发判断：全量（含 system）估算 token，没超预算直接原样返回
        int totalTokens = history.stream().mapToInt(this::estimateTokenCount).sum();
        if (totalTokens <= budget) {
            return history.stream().map(MessageConverter::toMessage).toList();
        }

        // 拆分在 ChatMessage 层做而不是 Message 层：
        // 水位线需要数据库 id，转成 Spring AI 的 Message 后 id 就丢了
        List<ChatMessage> systemMsgs = history.stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .toList();
        List<ChatMessage> normalMsgs = history.stream()
                .filter(m -> m.getMessageType() != MessageType.SYSTEM)
                .toList();

        try {
            ChatSummary existing = chatSummaryRepository.lambdaQuery()
                    .eq(ChatSummary::getChatId, conversationId)
                    .one();
            boolean hasValidSummary = existing != null
                    && existing.getSummary() != null && !existing.getSummary().isBlank()
                    && existing.getLastMessageId() != null;

            if (hasValidSummary) {
                // ---------- 增量路径：摘要已覆盖 [.. 水位线]，只处理水位线之后的新消息 ----------
                Long watermark = existing.getLastMessageId();
                List<ChatMessage> sinceW = normalMsgs.stream()
                        .filter(m -> m.getId() > watermark)
                        .toList();
                // 水位线之后没有新消息 → 摘要即全部，零模型调用
                if (sinceW.isEmpty()) {
                    log.info("水位线命中无新增，直接复用摘要, conversationId={}", conversationId);
                    return buildResult(systemMsgs, existing.getSummary(), Collections.emptyList());
                }
                int summaryTokens = estimateTokenCount(existing.getSummary());
                int sinceWTokens = sinceW.stream().mapToInt(this::estimateTokenCount).sum();
                // 摘要 + 新消息还在预算内 → 直接复用，不调模型
                if (summaryTokens + sinceWTokens <= budget) {
                    log.info("摘要+{}条新增未超预算，复用摘要, conversationId={}", sinceW.size(), conversationId);
                    return buildResult(systemMsgs, existing.getSummary(), sinceW);
                }
                // 超预算：把水位线后除最近 N 条之外的部分增量压缩进摘要
                if (sinceW.size() > RECENT_KEEP) {
                    List<ChatMessage> older = sinceW.subList(0, sinceW.size() - RECENT_KEEP);
                    List<ChatMessage> recent = sinceW.subList(sinceW.size() - RECENT_KEEP, sinceW.size());
                    log.info("摘要+新增超预算，增量压缩 {} 条, conversationId={}", older.size(), conversationId);
                    String merged = summarize(existing.getSummary(), older);
                    upsertSummary(conversationId, merged, older.get(older.size() - 1).getId());
                    return buildResult(systemMsgs, merged, recent);
                }
                // 新消息不足 N 条却已超预算（单条巨大）→ 交给硬裁剪兜底
                log.info("水位线后消息不足 N 条但超预算，走硬裁剪, conversationId={}", conversationId);
                return trimByTokens(history.stream().map(MessageConverter::toMessage).toList(), budget);
            }

            // ---------- 首次压缩路径：还没有有效摘要 ----------
            if (normalMsgs.size() <= RECENT_KEEP) {
                return trimByTokens(history.stream().map(MessageConverter::toMessage).toList(), budget);
            }
            List<ChatMessage> older = normalMsgs.subList(0, normalMsgs.size() - RECENT_KEEP);
            List<ChatMessage> recent = normalMsgs.subList(normalMsgs.size() - RECENT_KEEP, normalMsgs.size());
            log.info("首次摘要压缩 {} 条, conversationId={}", older.size(), conversationId);
            String summary = summarize(null, older);
            upsertSummary(conversationId, summary, older.get(older.size() - 1).getId());
            return buildResult(systemMsgs, summary, recent);
        } catch (Exception e) {
            // 降级：压缩失败不影响可用性，用硬裁剪兜底（预算必须用同一个 budget）
            log.warn("会话摘要压缩失败，降级为硬裁剪, conversationId={}", conversationId, e);
            return trimByTokens(history.stream().map(MessageConverter::toMessage).toList(), budget);
        }
    }

    /** 组装返回：SystemMessage（人设，必须在前）+ 摘要 + 水位线之后的原文 */
    private List<Message> buildResult(List<ChatMessage> systemMsgs, String summary, List<ChatMessage> recent) {
        List<Message> result = new ArrayList<>();
        systemMsgs.stream().map(MessageConverter::toMessage).forEach(result::add);
        // 摘要用 AssistantMessage 承载——对模型来说它是"更早的对话内容"，而不是用户说的话
        result.add(new AssistantMessage("以下是更早对话的摘要：" + summary));
        recent.stream().map(MessageConverter::toMessage).forEach(result::add);
        return result;
    }

    /** upsert 摘要：一个会话一条，首次 insert、后续 update；水位线标记摘要覆盖到哪条消息 */
    private void upsertSummary(String conversationId, String summary, Long watermark) {
        ChatSummary s = chatSummaryRepository.lambdaQuery()
                .eq(ChatSummary::getChatId, conversationId)
                .one();
        if (s == null) {
            s = new ChatSummary();
            s.setChatId(conversationId);
        }
        s.setSummary(summary);
        s.setLastMessageId(watermark);
        chatSummaryRepository.saveOrUpdate(s);
    }

    /**
     * 调模型生成/合并摘要。
     * existingSummary 非空时做增量合并（旧摘要 + 新增对话 → 新摘要），避免整段重压；
     * prompt 要求保留关键信息而不是泛泛总结，摘要质量直接决定模型对
     * "很久之前聊了什么"的感知。
     */
    private String summarize(String existingSummary, List<ChatMessage> toCompress) {
        String dialogue = toCompress.stream()
                .map(m -> m.getMessageType().getValue() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
        String prompt;
        if (existingSummary == null || existingSummary.isBlank()) {
            prompt = "请把以下对话压缩成一段简洁的摘要，必须保留：用户的诉求和偏好、"
                    + "已经确认的关键结论、尚未完成的任务。直接输出摘要内容，不要任何解释：\n\n"
                    + dialogue;
        } else {
            prompt = "下面是一段对话的已有摘要，以及摘要之后新增的对话。"
                    + "请把新增对话的信息合并进摘要，输出一份完整的最新摘要，"
                    + "必须保留：用户的诉求和偏好、已经确认的关键结论、尚未完成的任务。"
                    + "直接输出摘要内容，不要任何解释：\n【已有摘要】\n" + existingSummary
                    + "\n【新增对话】\n" + dialogue;
        }
        String summary = chatModel.call(prompt);
        log.info("摘要模型调用完成, 输入长度: {}, 返回长度: {}", prompt.length(), summary == null ? 0 : summary.length());
        // 空返回视为失败（内容风控/服务异常都可能只给空串不抛错），
        // 抛出去走降级硬裁剪，绝不把空摘要落库污染水位线
        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("模型返回空摘要");
        }
        return summary;
    }

    /**
     * 返回总 token 不超过 budget 的最近历史；放不下的老消息直接丢掉
     * （摘要压缩是下一步的事，今天先做"丢"）
     */
    public List<Message> trimByTokens(List<Message> history, int budget) {
        // 第0步：把 history 拆成两堆
        //   systemMsgs  —— instanceof SystemMessage 的
        //   normalMsgs  —— 其余的（保持原有顺序！）
        List<Message> normalMsgs = history
                .stream()
                .filter(message -> !(message instanceof SystemMessage))
                .toList();
        List<Message> systemMsgs = history
                .stream()
                .filter(message -> message instanceof SystemMessage)
                .toList();
        // 第1步：累加整条 history 的估算 token，得到 total
        //        （复用你已有的 estimateTokens）
        int total = normalMsgs
                .stream()
                .mapToInt(this::estimateTokenCount)
                .sum();
        // 第2步：total <= budget？→ 原样返回 history，什么都不动
        if (total <= budget) {
            return history;
        }
        // 第3步：从列表【末尾】往前遍历，边走边把消息的 token 累加到
        //        一个"已用"变量里；当"已用"超过 budget 时停下，
        //        记住停在了哪个位置（下标）
        //        —— 想一想：为什么从尾往头走，天然就符合"保留最近的"？
        List<Message> kept = new ArrayList<>();
        int used = 0;
        for (int i = normalMsgs.size() - 1; i >= 0; i--) {
            Message message = normalMsgs.get(i);
            int cost = estimateTokenCount(message);
            if (used + cost > budget) {
                break;
            }
            kept.add(message);
            used += cost;
        }
        // 第4步：返回从停下的位置到最后的那一段
        //        —— 注意方向：你是从尾往前走的，取出来的顺序要想清楚
        if (kept.isEmpty()) {
            kept.add(normalMsgs.get(normalMsgs.size() - 1));
        }
        Collections.reverse(kept);
        kept.addAll(0, systemMsgs);   // 明确表达"系统消息在最前"
        return kept;
    }

    // 估算token数量，粗估：1 个字符 ≈ 1.5 token
    private int estimateTokenCount(String text) {
        return text == null ? 0 : text.length() * 3 / 2;
    }

    private int estimateTokenCount(Message message) {
        return estimateTokenCount(message.getText());
    }

    private int estimateTokenCount(ChatMessage message) {
        return estimateTokenCount(message.getContent());
    }
}
