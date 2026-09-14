package com.lcl.yunpicturebackend.manager.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.config.AiAssistantProperties;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T10 代理转发单测（docs/plan.md T10）：转发给引擎的请求形状（路径 / query / API key / 凭据组）、
 * SSE 事件流逐帧中继、以及"引擎错误与不可达都要变成用户看得见的一条 answer"。
 * 桩引擎是进程内 JDK HttpServer，不依赖外部服务、不依赖 MySQL/Redis，进门禁。
 */
class AiAssistantProxyManagerTest {

    private static final String MESSAGE = "看看我的空间";
    private static final String SATOKEN = "tk-1";
    private static final String SESSION_ID = "sess-1";
    private static final String API_KEY = "key-1";

    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static volatile String stubMethod;
    private static volatile String stubUri;
    private static volatile String stubApiKey;
    private static volatile String stubSatoken;
    private static volatile String stubCookie;
    private static volatile String stubAccept;
    private static volatile int stubStatus;
    private static volatile String stubContentType;
    private static volatile String stubBody;

    private static HttpServer engine;
    private static String engineBaseUrl;
    private static ExecutorService executor;

    @BeforeAll
    static void startStubEngine() throws IOException {
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engine.createContext("/", AiAssistantProxyManagerTest::handle);
        engine.setExecutor(Executors.newCachedThreadPool());
        engine.start();
        engineBaseUrl = "http://127.0.0.1:" + engine.getAddress().getPort() + "/api";
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterAll
    static void stopStubEngine() {
        engine.stop(0);
        executor.shutdownNow();
    }

    @BeforeEach
    void resetStub() {
        REQUESTS.set(0);
        stubMethod = null;
        stubUri = null;
        stubApiKey = null;
        stubSatoken = null;
        stubCookie = null;
        stubAccept = null;
        stubStatus = 200;
        stubContentType = "text/event-stream; charset=utf-8";
        stubBody = "";
    }

    private static void handle(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        stubMethod = exchange.getRequestMethod();
        stubUri = exchange.getRequestURI().toString();
        stubApiKey = exchange.getRequestHeaders().getFirst("X-Internal-Api-Key");
        stubSatoken = exchange.getRequestHeaders().getFirst("satoken");
        stubCookie = exchange.getRequestHeaders().getFirst("Cookie");
        stubAccept = exchange.getRequestHeaders().getFirst("Accept");
        byte[] body = stubBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", stubContentType);
        // 0 = chunked：SSE 的真实形态（不定长、写完即关）
        exchange.sendResponseHeaders(stubStatus, stubContentType.contains("text/event-stream") ? 0 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
            out.flush();
        }
    }

    private AiAssistantProperties properties(String baseUrl, String apiKey) {
        AiAssistantProperties properties = new AiAssistantProperties();
        properties.setEngineBaseUrl(baseUrl);
        properties.setInternalApiKey(apiKey);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private RecordingManager manager() {
        return new RecordingManager(properties(engineBaseUrl, API_KEY), executor);
    }

    private Map<String, String> queryOf(String uri) {
        Map<String, String> params = new HashMap<>();
        int index = uri.indexOf('?');
        if (index < 0) {
            return params;
        }
        for (String pair : uri.substring(index + 1).split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    @Test
    void forwardsCredentialGroupAndRelaysSseFramesVerbatim() {
        stubBody = "data:{\"event\":\"step\",\"kind\":\"think\",\"name\":\"思考\",\"content\":\"先看看空间\"}\n\n"
                + "data:{\"event\":\"answer\",\"content\":\"你有 1 个空间\"}\n\n"
                + "data:[DONE]\n\n";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).containsExactly(
                "{\"event\":\"step\",\"kind\":\"think\",\"name\":\"思考\",\"content\":\"先看看空间\"}",
                "{\"event\":\"answer\",\"content\":\"你有 1 个空间\"}",
                "[DONE]");
        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(stubMethod).isEqualTo("GET");
        assertThat(stubUri).startsWith("/api/ai/huoshan/chat?");
        assertThat(queryOf(stubUri))
                .containsEntry("message", MESSAGE)
                .containsEntry("userId", "123")
                .containsEntry("chatId", "chat-1");
        assertThat(stubApiKey).isEqualTo(API_KEY);
        assertThat(stubSatoken).isEqualTo(SATOKEN);
        assertThat(stubCookie).isEqualTo("SESSION=" + SESSION_ID);
        assertThat(stubAccept).contains("text/event-stream");
    }

    @Test
    void omitsChatIdWhenBlank() {
        stubBody = "data:[DONE]\n\n";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, null, SATOKEN, SESSION_ID);
        manager.emitter.awaitFrames();

        assertThat(queryOf(stubUri)).doesNotContainKey("chatId");
    }

    @Test
    void toleratesSseFormattingWithoutLosingOrMergingFrames() {
        // 冒号后带空格（规范允许）、注释行、event 字段行、空帧都不该打乱帧边界
        stubBody = ": keep-alive\n\n"
                + "event: message\n"
                + "data: {\"event\":\"answer\",\"content\":\"a\"}\n\n"
                + "data:{\"event\":\"answer\",\"content\":\"b\"}\n\n"
                + "data:[DONE]\n\n";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).containsExactly(
                "{\"event\":\"answer\",\"content\":\"a\"}",
                "{\"event\":\"answer\",\"content\":\"b\"}",
                "[DONE]");
    }

    @Test
    void upstreamUnauthorizedErrorBecomesVisibleAnswer() {
        stubStatus = 401;
        stubContentType = "application/json;charset=UTF-8";
        stubBody = "{\"code\":40100,\"data\":null,\"message\":\"内部接口未授权：缺少或错误的 X-Internal-Api-Key\"}";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).contains("\"event\":\"answer\"").contains("内部接口未授权");
        assertThat(frames.get(1)).isEqualTo("[DONE]");
    }

    @Test
    void upstreamJsonErrorWithOkStatusAlsoBecomesVisibleAnswer() {
        stubStatus = 200;
        stubContentType = "application/json";
        stubBody = "{\"code\":50000,\"data\":null,\"message\":\"图库助手模型未配置：请设置 app.ai.openai.assistant.api-key 与 model\"}";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).contains("图库助手模型未配置");
        assertThat(frames.get(1)).isEqualTo("[DONE]");
    }

    @Test
    void unreachableEngineBecomesVisibleAnswer() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        RecordingManager manager = new RecordingManager(
                properties("http://127.0.0.1:" + closedPort + "/api", API_KEY), executor);
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).contains("暂时不可用");
        assertThat(frames.get(1)).isEqualTo("[DONE]");
    }

    @Test
    void failsClosedWhenEngineKeyMissing() {
        RecordingManager manager = new RecordingManager(properties(engineBaseUrl, " "), executor);

        assertThatThrownBy(() -> manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("引擎密钥未配置")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
        assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void failsClosedWhenEngineUrlMissing() {
        RecordingManager manager = new RecordingManager(properties("", API_KEY), executor);

        assertThatThrownBy(() -> manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("引擎地址未配置");
        assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void reportsFailureLoudlyWhenExecutorIsSaturated() {
        RecordingManager manager = new RecordingManager(properties(engineBaseUrl, API_KEY), new RejectingExecutor());

        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID);

        assertThat(manager.emitter.awaitFrames()).isEmpty();
        assertThat(manager.emitter.failed).isTrue();
        assertThat(REQUESTS.get()).isZero();
    }

    /** 记录型 emitter：不绑 servlet 响应，直接收集 send() 的负载，断言确定性 */
    private static class RecordingSseEmitter extends SseEmitter {

        private final List<String> frames = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile boolean failed;

        @Override
        public void send(Object object, MediaType mediaType) {
            frames.add(String.valueOf(object));
        }

        @Override
        public void send(Object object) {
            frames.add(String.valueOf(object));
        }

        @Override
        public void complete() {
            super.complete();
            finished.countDown();
        }

        @Override
        public void completeWithError(Throwable ex) {
            failed = true;
            finished.countDown();
        }

        List<String> awaitFrames() {
            try {
                assertThat(finished.await(10, TimeUnit.SECONDS)).as("emitter 应在 10s 内收尾").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 emitter 收尾被中断", e);
            }
            synchronized (frames) {
                return new ArrayList<>(frames);
            }
        }
    }

    /** 覆写 createEmitter 注入记录型 emitter；其余行为与生产一致 */
    private static class RecordingManager extends AiAssistantProxyManager {

        private final RecordingSseEmitter emitter = new RecordingSseEmitter();

        RecordingManager(AiAssistantProperties properties, ExecutorService executor) {
            super(properties, new ObjectMapper(), executor);
        }

        @Override
        protected SseEmitter createEmitter() {
            return emitter;
        }
    }

    private static class RejectingExecutor extends java.util.concurrent.AbstractExecutorService {

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("线程池已满");
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Arrays.asList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
