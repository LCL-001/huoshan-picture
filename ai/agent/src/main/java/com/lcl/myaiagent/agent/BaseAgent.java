package com.lcl.myaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.myaiagent.agent.event.AgentEvent;
import com.lcl.myaiagent.agent.event.AgentEventListener;
import com.lcl.myaiagent.agent.event.SseAgentEventListener;
import com.lcl.myaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 智能体基类，提供基础的AI Agent执行框架
 * <p>
 * 该抽象类定义了智能体的核心运行逻辑，包括状态管理、步骤控制、记忆系统等功能。
 * 子类需要实现step()方法定义单步执行逻辑，以及cleanUp()方法进行资源清理。
 * </p>
 * <p>
 * <b>实例生命周期不变量：一个 agent 实例只跑一次</b>——要再跑请新建实例。生产路径由控制器按请求新建
 * （{@code HuoshanAssistantAgent} 因此不能做单例 Bean）。这条不被入口守卫强制：{@code runLoop}
 * 只要求进入时状态为 {@code IDLE}，所以"正常跑完一次"的实例技术上能再跑，只是 {@code messageList}
 * 会把上一轮会话带进来。真正让实例不可复用的是两处状态粘连——{@code cleanUp()} 故意不把
 * {@code ERROR} 复位成 {@code IDLE}（见 {@code ToolCallAgent.cleanUp()}），以及用户"停止生成"置位的
 * {@code stopped} 不复位。
 * </p>
 */
@Slf4j
@Data
public abstract class BaseAgent {

    /** SSE 连接超时（与后端代理 AiAssistantProxyManager.EMITTER_TIMEOUT_MS 对齐） */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    /** 失败文案：超时与一般失败各一句固定文案，原文只进日志（T8-c，见 AgentFailureEventTest） */
    private static final String TIMEOUT_FAILURE_TEXT = "助手响应超时，请重试";
    private static final String GENERIC_FAILURE_TEXT = "助手处理失败，请稍后重试";

    // 智能体名称
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 智能体状态
    private AgentState state = AgentState.IDLE;// 默认为空闲状态

    // 用户"停止生成"标记：前端断开 SSE 连接时置位，执行循环每轮检查后提前退出。
    // volatile 保证异步执行线程能立即看到置位
    private volatile boolean stopped = false;

    // 智能体执行控制
    private int currentStep = 0;
    private int maxSteps = 10;

    // LLM模型
    private ChatClient chatClient;

    // 记忆系统(本轮工作集，自主维护)：跨轮历史在 historyContext，发给模型的是两者拼接
    private List<Message> messageList = new ArrayList<>();

    // 会话 ID：跨轮记忆按它定位会话（loadConversationHistory / remember* 用）
    private String conversationId;

    /**
     * 跨轮记忆（2026-09-27 起显式读写，替代 MessageChatMemoryAdvisor）。
     * <p>
     * advisor 的语义是"每次模型调用都重读全量记忆、整段注入请求消息之前，再把请求里最后一条用户消息
     * 回写一遍"。而本引擎的循环每轮都重提整段 messageList——两者叠加后，同一段上下文每轮在提示词里
     * 出现两份（记忆视图一份 + messageList 一份）并逐轮膨胀；同一条用户消息每轮回写一次（入库侧靠
     * 指纹去重兜住，提示词侧没人兜）。改为显式读写：运行开始读一次压缩视图作为固定前缀
     * （{@link #historyContext}）、写一次用户消息；每步模型调用后写一次助手消息（ToolCallAgent.think）。
     * 为 null 时（单测直接 new 的裸 agent）跳过全部记忆读写。
     * </p>
     */
    private ChatMemory chatMemory;

    /** 本轮装载的跨轮记忆前缀：运行开始读一次、整轮不变；发给模型的提示词 = 它 + messageList */
    private List<Message> historyContext = List.of();

    // 运行期计数（R1 埋点）：超时与提前终止各一个计数器；不注入时为 null，循环照常跑
    private AgentRunMetrics metrics;

    // 循环检测：滑窗内同一循环签名的触发次数（含当前），见 isStuck
    private int duplicateThreshold = 2;

    // 陷入循环计数器：第 1 次纠偏、第 2 次强停（T33 收紧；旧口径 3 次要白烧约 5 步预算）
    private int stuckCount = 0;
    private static final int MAX_STUCK_COUNT = 2;

    /** 循环检测滑窗宽度（assistant 子序列的最后 N 步）；参考 Strands 的滑窗指纹与 Inngest 的连续口径 */
    private static final int STUCK_WINDOW = 4;

    /** 卡死提示词前缀：该提示只拼入本轮请求、不入库（2026-09-27 起，advisor 的回写移除后
     * 内部提示不再落库）；前端历史里旧时代的内部提示行仍由 ChatHistoryAssembler 按前缀过滤 */
    public static final String STUCK_PROMPT_PREFIX = "观察到重复响应";

    /** 循环签名的参数规范化用（只做内存比较，与持久化序列化无关） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 以流式方式运行智能体，通过SSE（Server-Sent Events）实时返回执行结果
     * <p>
     * 运行本身收敛在 {@link #runLoop(String, AgentEventListener)}：本方法只负责备好事件流载体、
     * 挂上连接生命周期回调，再把循环交给异步线程。循环产出的每一步都经
     * {@link SseAgentEventListener} 翻成帧（step/answer/metrics/[DONE]）。
     * </p>
     *
     * @param userPrompt 用户输入的提示信息，不能为空
     * @return SseEmitter SSE发射器对象，用于向客户端推送流式响应
     */
    public SseEmitter runStream(String userPrompt) {
        return runStream(userPrompt, List.of());
    }

    /**
     * 带"开场事件"的流式运行（T22）：开场事件在**循环之前**按序发出（例：搜图 MCP 降级提示），
     * 校验失败（非 IDLE / 空提示词）时**不发**——那两条路径只回一条错误回答。
     *
     * @param openingEvents 本轮开头要发的事件，可为空列表；不得包含 Done（收尾由循环负责）
     */
    public SseEmitter runStream(String userPrompt, List<AgentEvent> openingEvents) {
        SseEmitter emitter = createEmitter();
        // 回调只改状态与停止标记，不直接向 emitter 写数据——收尾统一由 SseAgentEventListener 负责
        emitter.onError(e -> {
            // 前端"停止生成"会断开连接，容器在下一次写入失败时触发此回调：
            // 置停止标记，让执行循环在当前步结束后立即退出，不再发起后续 LLM 调用
            this.stopped = true;
            log.info("SSE connection error (client may have stopped generation).");
        });
        emitter.onTimeout(() -> {
            this.stopped = true;
            this.state = AgentState.ERROR;
            this.cleanUp();
            log.warn("SSE connection timed out.");
        });
        emitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanUp();
            log.info("SSE connection completed.");
        });
        CompletableFuture.runAsync(() -> runLoop(userPrompt,
                new SseAgentEventListener(emitter, () -> this.stopped = true), openingEvents));
        return emitter;
    }

    /**
     * 事件流载体：单点定义，便于测试覆写为记录型 emitter 断言帧序列
     * （与后端 AiAssistantProxyManager.createEmitter() 同一手法）。
     */
    protected SseEmitter createEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MS);
    }

    /**
     * 唯一的运行循环——T8-hard 之前 run() 与 runStream() 各有一份，现已收敛到这里。
     * <p>
     * 不变量：**每条流以恰好一个 Done 事件收尾**（校验失败、达最大步数、卡死终止、用户停止、
     * 异常终止都走同一处收尾），消费端据此确认流正常结束；除 Done 外的事件由子类
     * {@link #step()} 产出。
     * <p>
     * 可在测试中同步驱动（配收集型监听器）；生产路径由 {@link #runStream(String)} 异步驱动。
     */
    protected void runLoop(String userPrompt, AgentEventListener listener) {
        runLoop(userPrompt, listener, List.of());
    }

    /**
     * 唯一的运行循环的重载形态：多一个"开场事件"列表（T22），在进入循环前按序发出。
     * <p>
     * 只在校验通过后发——非 IDLE / 空提示词两条路径直接返回，不发开场事件（那两条只回一条错误回答）。
     * 放在 loop 的 try 里发，保证"发出即收尾"的语义与其它路径一致（异常照样以 error + Done 收尾）。
     * </p>
     *
     * @param openingEvents 本轮开头要发的事件（如降级提示），不得包含 Done
     */
    protected void runLoop(String userPrompt, AgentEventListener listener, List<AgentEvent> openingEvents) {
        // 校验：不抛异常，失败以事件告知消费端（端点是 SSE，客户端读不到 HTTP 错误体）
        if (this.state != AgentState.IDLE) {
            listener.onEvent(new AgentEvent.Answer("助手正忙，请稍后再试"));
            listener.onEvent(new AgentEvent.Done());
            return;
        }
        if (StrUtil.isBlank(userPrompt)) {
            listener.onEvent(new AgentEvent.Answer("这条消息是空的，请重新输入"));
            listener.onEvent(new AgentEvent.Done());
            return;
        }
        // 更改状态
        this.state = AgentState.RUNNING;
        // 重置循环计数器
        this.stuckCount = 0;
        // 记录上下文（本轮工作集）；跨轮记忆的读写（有 DB 往返）放在下面 try 里，
        // 故障沿用既有契约以 error 事件收尾，不让异步线程把异常吞掉
        this.messageList.add(new UserMessage(userPrompt));

        try {
            // 跨轮记忆显式读写（替代 advisor 的每轮重读重注）：先读（压缩视图里还没有本轮消息，
            // 顺序反了本轮提问会在前缀里重复一份），再写（用户消息只落一次库）
            this.historyContext = loadConversationHistory();
            this.rememberUserMessage(userPrompt);
            // 开场事件（T22）：本轮的环境说明先于循环产出，例如"搜图服务暂不可用"
            for (AgentEvent opening : openingEvents) {
                listener.onEvent(opening);
            }
            // 执行
            while (this.currentStep < this.maxSteps && this.state != AgentState.FINISHED && !this.stopped) {
                int stepNumber = ++this.currentStep;
                log.info("Executing step: {}/{}", stepNumber, this.maxSteps);
                // 单步执行，逐条事件发给消费端
                for (AgentEvent event : this.step()) {
                    listener.onEvent(event);
                }
                // 每一步 step 执行完都要检查是否陷入循环；本步已给出最终回答（FINISHED）时不再检测——
                // 循环已自然结束，重复的收尾回答不该被追加一句"助手重复了同样的步骤"（T33 修复双 answer）
                if (this.state != AgentState.FINISHED && isStuck()) {
                    handleStuckState();
                    if (this.state == AgentState.FINISHED) {
                        listener.onEvent(new AgentEvent.Answer("助手重复了同样的步骤，已停止本次回答"));
                        break;
                    }
                }
            }
            if (this.stopped) {
                // 用户点了"停止生成"：连接已断开，不再向它写数据，
                // 终止执行（当前这步的 LLM 调用无法中断，但后续步骤不再执行）
                this.state = AgentState.FINISHED;
                countCancelled();
                log.info("用户停止生成，Agent 提前终止, steps={}", this.currentStep);
            } else {
                // 检查是否超出步骤限制：只在"仍在运行"时才算真撞上限——最终回答恰好落在第 maxSteps 步时
                // state 已是 FINISHED，不该再补一条上限提示（卡死终止落在同一步时同理）
                if (this.state == AgentState.RUNNING && this.currentStep >= this.maxSteps) {
                    this.state = AgentState.FINISHED;
                    listener.onEvent(new AgentEvent.Answer("这次回答达到步数上限（" + this.maxSteps + " 步）"));
                }
                emitMetrics(listener);
            }
        } catch (Exception e) {
            // 终止性失败（provider 超时、调用失败、工具或内部异常）：本轮以一条 error 收尾并结束。
            // 不再把异常文本当回答发出去——原文只进日志（T8-c：原先同样的原始异常文本会重复多轮）
            this.state = AgentState.ERROR;
            if (isTimeout(e)) {
                countTimeout();
            }
            log.error("{} 执行失败，本轮终止：{}", getName(), e.getMessage(), e);
            listener.onEvent(new AgentEvent.Error(failureText(e)));
        } finally {
            // 收尾：恰好一个 Done（缺口"卡死路径双发 [DONE]""校验失败流不以 [DONE] 收尾"在此修正）
            listener.onEvent(new AgentEvent.Done());
            // 清理资源
            this.cleanUp();
        }
    }

    /**
     * 跨轮记忆读取（一次运行只调一次）：取压缩视图并去掉 system 行——
     * system 每次模型调用由 {@code .system()} 现场提供，不能从历史前缀再来一份
     */
    private List<Message> loadConversationHistory() {
        if (this.chatMemory == null || this.conversationId == null) {
            return List.of();
        }
        return this.chatMemory.get(this.conversationId).stream()
                .filter(message -> !(message instanceof SystemMessage))
                .toList();
    }

    /** 用户消息写回：一次运行只落一次库（advisor 时代每轮回写、靠指纹去重兜底，现显式只写一次） */
    private void rememberUserMessage(String userPrompt) {
        if (this.chatMemory == null || this.conversationId == null) {
            return;
        }
        this.chatMemory.add(this.conversationId, List.of(new UserMessage(userPrompt)));
    }

    /** 每步模型调用的产出写回一次（原 advisor after() 的职责收编到这里；工具响应行仍不落库） */
    protected void rememberAssistantMessage(AssistantMessage assistantMessage) {
        if (this.chatMemory == null || this.conversationId == null) {
            return;
        }
        this.chatMemory.add(this.conversationId, List.of(assistantMessage));
    }

    /**
     * 失败 → 面向用户的固定文案（T8-c）。原始异常只进日志：用户不需要（也不该）读到厂商报错原文。
     */
    private static String failureText(Throwable failure) {
        return isTimeout(failure) ? TIMEOUT_FAILURE_TEXT : GENERIC_FAILURE_TEXT;
    }

    /** R1 埋点：不注入计数器时是空操作（测试与 MyManus 旧链路不受影响） */
    private void countTimeout() {
        if (this.metrics != null) {
            this.metrics.timeout();
        }
    }

    private void countCancelled() {
        if (this.metrics != null) {
            this.metrics.cancelled();
        }
    }

    /**
     * 沿 cause 链判超时。两种形态都取自 T6.1 实测（docs/decisions.md 2026-09-14）：
     * 流空闲超时是 {@link TimeoutException}（外层是状态码 200 的 WebClientResponseException），
     * 连接/首包超时是 {@link HttpTimeoutException}（外层是 WebClientRequestException）。
     * 只看 cause 链是因为外层类型由框架决定、随版本变化。
     */
    private static boolean isTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof HttpTimeoutException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * 运行结束时的指标汇总（正常收尾路径、Done 之前以 event=metrics 发出）。
     * 默认返回 null 不发事件；子类按需提供（如检索/来源计数）。
     * 用户主动停止、SSE 超时、异常结束不发——指标只描述完整跑完的运行。
     * <p>
     * 注意：返回值的键会平铺进帧里，键名不得取 event/kind/name/content。
     */
    protected Map<String, Object> runSummary() {
        return null;
    }

    private void emitMetrics(AgentEventListener listener) {
        Map<String, Object> summary = runSummary();
        if (summary != null) {
            listener.onEvent(new AgentEvent.Metrics(summary));
        }
    }

    /**
     * 执行智能体的单步操作
     * <p>
     * 子类必须实现此方法来定义具体的单步执行逻辑。返回本步要发给消费端的事件（通常 1-2 条：
     * 工具步是"思考 + 工具结果"两条，回答步是 1 条），循环按序发出——T8-hard 之前靠
     * lastStepKind/lastThinkText/lastToolNames 三个受保护字段旁路给循环传分类与明细，现已收编。
     * </p>
     *
     * @return 本步产出的事件，可为空列表（不发任何帧）
     */
    public abstract List<AgentEvent> step();

    /**
     * 清理智能体占用的资源
     * <p>
     * 在runLoop()执行完成后（无论正常结束还是异常）都会被调用，
     * 子类应在此方法中实现必要的资源清理逻辑。实现须幂等：循环收尾与 SSE 完成回调都会调用它。
     * </p>
     */
    protected abstract void cleanUp();

    /**
     * 处理陷入循环的状态（T33 收紧：第 1 次纠偏、第 2 次强停）
     */
    protected void handleStuckState() {
        stuckCount++;
        if (stuckCount >= MAX_STUCK_COUNT) {
            log.warn("Agent stuck {} times, forcing termination", stuckCount);
            this.state = AgentState.FINISHED;
            return;
        }
        // 纠偏提示只进下一轮请求、不入库（T32 起 advisor 回写移除）；
        // think() 每轮消费后置空，这里重新注入一轮
        String stuckPrompt = STUCK_PROMPT_PREFIX + "。考虑新策略，避免重复已尝试过的无效路径。";
        this.nextStepPrompt = stuckPrompt;
        log.warn("Agent detected stuck state ({} / {}). Added prompt: {}", stuckCount, MAX_STUCK_COUNT, stuckPrompt);
    }

    /**
     * 检查代理是否陷入循环（T33 重写）。
     * <p>
     * 旧实现（OpenManus 同款）是"最后一条 assistant 文本在整段历史里出现 ≥2 次"的全局计数，
     * 有三类问题：① 空文本的工具决策行直接让检测失明——"同工具同参数反复调用"这条最常见的
     * 死循环形态完全抓不到；② 全局计数把不同上下文里合法重复的短句误判成卡死；③ 精确等值匹配
     * 又对微小措辞差异漏报。现改为在 assistant 子序列的"循环签名"上做滑窗判定：
     * 最近 {@link #STUCK_WINDOW} 步内同一签名出现 ≥ {@link #duplicateThreshold} 次（含当前）即判卡死。
     * 工具签名抓"同工具同参数反复调用"（主信号，业界共识参考 Strands/Inngest），
     * 文本签名兜"模型复读"形态；签名只做内存比较，不进提示词与持久化。
     * </p>
     *
     * @return 是否陷入循环
     */
    protected boolean isStuck() {
        List<String> signatures = new ArrayList<>();
        for (Message message : getMessageList()) {
            String signature = loopSignature(message);
            if (signature != null) {
                signatures.add(signature);
            }
        }
        int from = Math.max(0, signatures.size() - STUCK_WINDOW);
        List<String> window = signatures.subList(from, signatures.size());
        for (String candidate : window) {
            int count = 0;
            for (String signature : window) {
                if (candidate.equals(signature)) {
                    count++;
                }
            }
            if (count >= this.duplicateThreshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单步的循环签名：工具调用步 = "tool|名|规范化参数"（一次多调用按序拼接），
     * 纯文本步 = "text|文本"；空文本且无工具调用的决策行无签名（不参与计数）。
     * 带文本的工具决策行按工具签名判——文本只是模型的修辞变化，重复的动作才是循环信号。
     */
    private String loopSignature(Message message) {
        if (!(message instanceof AssistantMessage assistantMessage)) {
            return null;
        }
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            return toolCalls.stream()
                    .map(toolCall -> "tool|" + toolCall.name() + "|" + canonicalArguments(toolCall.arguments()))
                    .collect(Collectors.joining(";"));
        }
        String text = assistantMessage.getText();
        if (text != null && !text.isBlank()) {
            return "text|" + text.trim();
        }
        return null;
    }

    /** 参数规范化：JSON 串重序列化去掉空白差异（键序重排不归一，属可接受漏报）；非 JSON 按原文 */
    private static String canonicalArguments(String arguments) {
        if (arguments == null) {
            return "";
        }
        try {
            return MAPPER.readTree(arguments).toString();
        } catch (JsonProcessingException e) {
            return arguments.trim();
        }
    }

}
