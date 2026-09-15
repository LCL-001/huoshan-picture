package com.lcl.myaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.lcl.myaiagent.agent.event.AgentEvent;
import com.lcl.myaiagent.agent.event.AgentEventListener;
import com.lcl.myaiagent.agent.event.SseAgentEventListener;
import com.lcl.myaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

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

    // 记忆系统(Memory 自主维护)
    private List<Message> messageList = new ArrayList<>();

    // 会话 ID：Advisor 读写外部 ChatMemory 时用它定位会话
    private String conversationId;

    // 运行期计数（R1 埋点）：超时与提前终止各一个计数器；不注入时为 null，循环照常跑
    private AgentRunMetrics metrics;

    // 添加重复内容阈值
    private int duplicateThreshold = 2;

    // 陷入循环计数器
    private int stuckCount = 0;
    private static final int MAX_STUCK_COUNT = 3;

    // 卡死提示词前缀：该提示会作为 USER 消息进入对话并被持久化，
    // 前端渲染历史时靠它识别并过滤 agent 内部提示
    public static final String STUCK_PROMPT_PREFIX = "观察到重复响应";

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
            listener.onEvent(new AgentEvent.Answer("错误：无法从该状态运行代理：" + this.state));
            listener.onEvent(new AgentEvent.Done());
            return;
        }
        if (StrUtil.isBlank(userPrompt)) {
            listener.onEvent(new AgentEvent.Answer("错误：用户提示不能为空。"));
            listener.onEvent(new AgentEvent.Done());
            return;
        }
        // 更改状态
        this.state = AgentState.RUNNING;
        // 重置循环计数器
        this.stuckCount = 0;
        // 记录上下文
        this.messageList.add(new UserMessage(userPrompt));

        try {
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
                // 每一步 step 执行完都要检查是否陷入循环
                if (isStuck()) {
                    handleStuckState();
                    if (this.state == AgentState.FINISHED) {
                        listener.onEvent(new AgentEvent.Answer("检测到循环，智能体已终止"));
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
                    listener.onEvent(new AgentEvent.Answer("执行结束：达到最大步骤 (" + this.maxSteps + ")"));
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
     * 处理陷入循环的状态
     */
    protected void handleStuckState() {
        stuckCount++;
        if (stuckCount >= MAX_STUCK_COUNT) {
            log.warn("Agent stuck {} times, forcing termination", stuckCount);
            this.state = AgentState.FINISHED;
            return;
        }
        String stuckPrompt = STUCK_PROMPT_PREFIX + "。考虑新策略，避免重复已尝试过的无效路径。";
        this.nextStepPrompt = stuckPrompt + "\n" + (this.nextStepPrompt != null ? this.nextStepPrompt : "");
        log.warn("Agent detected stuck state ({} / {}). Added prompt: {}", stuckCount, MAX_STUCK_COUNT, stuckPrompt);
    }

    /**
     * 检查代理是否陷入循环
     * 查找最后一条 ASSISTANT 消息，检测是否与历史 ASSISTANT 消息重复
     *
     * @return 是否陷入循环
     */
    protected boolean isStuck() {
        List<Message> messages = getMessageList();
        if (messages.size() < 2) {
            return false;
        }

        // 从末尾查找最后一条 ASSISTANT 消息（跳过 ToolResponseMessage 等）
        Message lastAssistantMsg = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg.getMessageType() == MessageType.ASSISTANT) {
                lastAssistantMsg = msg;
                break;
            }
        }

        if (lastAssistantMsg == null
                || lastAssistantMsg.getText() == null
                || lastAssistantMsg.getText().isEmpty()) {
            return false;
        }

        // 通过引用查找最后一条 ASSISTANT 消息的位置（不能用 indexOf，因为 equals 是按内容比较）
        int lastIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) == lastAssistantMsg) {
                lastIndex = i;
                break;
            }
        }
        // 计算该 ASSISTANT 消息在历史 ASSISTANT 消息中的重复次数
        int duplicateCount = 0;
        for (int i = lastIndex - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg.getMessageType() == MessageType.ASSISTANT
                    && lastAssistantMsg.getText().equals(msg.getText())) {
                duplicateCount++;
            }
        }

        return duplicateCount >= this.duplicateThreshold;
    }

}
