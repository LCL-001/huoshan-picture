package com.lcl.myaiagent.agent.event;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 记录型 emitter（测试替身，沿用后端 AiAssistantProxyManagerTest.RecordingSseEmitter 的手法）：
 * 不绑 servlet 响应，直接收集 send() 的负载，让帧断言确定可复现。
 * <p>
 * 两个 send 重载都要覆写——只覆写一个会漏帧。
 */
public class RecordingSseEmitter extends SseEmitter {

    /** 一帧：JSON 事件帧的 payload 是 Map（由 Spring 序列化），终止帧是原始字符串 "[DONE]" */
    public record Frame(Object payload, MediaType mediaType) {

        public boolean isDoneFrame() {
            return SseAgentEventListener.DONE_FLAG.equals(payload);
        }
    }

    private final List<Frame> frames = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile boolean failed;
    private volatile boolean sendFails;

    public RecordingSseEmitter() {
        super(300_000L);
    }

    /** 模拟客户端断开：后续写入一律抛 IOException（触发监听器的"客户端已断开"回调） */
    public void failOnSend() {
        this.sendFails = true;
    }

    @Override
    public void send(Object object, MediaType mediaType) throws IOException {
        record(object, mediaType);
    }

    @Override
    public void send(Object object) throws IOException {
        record(object, null);
    }

    private void record(Object object, MediaType mediaType) throws IOException {
        if (sendFails) {
            throw new IOException("client gone");
        }
        frames.add(new Frame(object, mediaType));
    }

    @Override
    public void complete() {
        super.complete();
        completed.countDown();
    }

    @Override
    public void completeWithError(Throwable ex) {
        this.failed = true;
        completed.countDown();
    }

    public boolean awaitCompletion(long millis) {
        try {
            return completed.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 emitter 收尾被中断", e);
        }
    }

    public List<Frame> frames() {
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }

    /** 帧里承载的 payload 列表（JSON 帧是 Map，终止帧是 "[DONE]"） */
    public List<Object> payloads() {
        return frames().stream().map(Frame::payload).toList();
    }

    public boolean failed() {
        return failed;
    }
}
