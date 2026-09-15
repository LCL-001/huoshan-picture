package com.lcl.myaiagent.agent.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 {@link AgentEvent} 翻成 SSE 帧写进 {@link SseEmitter}（T8-hard）。
 * <p>
 * 帧格式（契约见 docs/plans/2026-09-15-T8hard-循环收敛实施计划.md，帧级断言见
 * {@code SseEventProtocolContractTest}）：
 * <ul>
 *   <li>Step / Answer / Metrics → {@code data:{JSON 对象}}，对象里有 {@code event} 字段区分类型；</li>
 *   <li>Done → {@code data:[DONE]}（**原始文本，不是 JSON**——代理按原样识别终止帧），随后 complete()。</li>
 * </ul>
 * 本类不改 agent 状态：写失败（客户端点了"停止生成"或连接已断）时回调 onClientGone，
 * 由调用方决定怎么终止循环。
 */
@Slf4j
public class SseAgentEventListener implements AgentEventListener {

    /** 终止帧的原始文本：代理与前端都按原样比较它 */
    public static final String DONE_FLAG = "[DONE]";

    private final SseEmitter emitter;
    private final Runnable onClientGone;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    /**
     * @param onClientGone 连接已不可用时的回调（通常是置 agent 的停止标记）
     */
    public SseAgentEventListener(SseEmitter emitter, Runnable onClientGone) {
        this.emitter = emitter;
        this.onClientGone = onClientGone;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event instanceof AgentEvent.Done) {
            finish();
            return;
        }
        try {
            emitter.send(frameOf(event), MediaType.APPLICATION_JSON);
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开（用户点了"停止生成"）：连接已废弃，错误无处上报
            log.debug("SSE 事件写入失败，客户端可能已断开");
            onClientGone.run();
        }
    }

    private void finish() {
        if (!finished.compareAndSet(false, true)) {
            // 循环保证每条流只发一个 Done，重复即协议违例：响亮记下而不是静默吞掉
            log.warn("收到重复的结束事件，已忽略：每条流只应有一个 Done");
            return;
        }
        try {
            emitter.send(DONE_FLAG);
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 结束帧写入失败，客户端可能已断开");
            onClientGone.run();
        } finally {
            emitter.complete();
        }
    }

    /** 事件 → data 帧。Done 不是 JSON 帧，走 finish()，这里按穷尽性列出来但不可达 */
    private Map<String, Object> frameOf(AgentEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (event) {
            case AgentEvent.Step step -> {
                payload.put("kind", step.kind());
                payload.put("name", step.name());
                payload.put("content", step.content());
                payload.put("event", "step");
            }
            case AgentEvent.Answer answer -> {
                payload.put("content", answer.content());
                payload.put("event", "answer");
            }
            case AgentEvent.Metrics metrics -> {
                // 指标平铺进帧里；values 不得使用 event/kind/name/content 作键
                payload.putAll(metrics.values());
                payload.put("event", "metrics");
            }
            case AgentEvent.Error error -> {
                payload.put("content", error.content());
                payload.put("event", "error");
            }
            case AgentEvent.Done ignored -> throw new IllegalStateException("Done 不走 JSON 帧");
        }
        return payload;
    }
}
