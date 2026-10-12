package com.lcl.yunpicturebackend.manager.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.config.AiAssistantProperties;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T10 代理转发单测（docs/plan.md T10）：转发给引擎的请求形状（路径 / query / API key / 凭据组）、
 * SSE 事件流逐帧中继、以及"引擎错误与不可达都要变成用户看得见的一条 error 帧"（T39 起不再合成 answer）。
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
    private static volatile String stubCustomToken;
    private static volatile String stubCookie;
    private static volatile String stubAccept;
    /** T17：透传给引擎的调用者角色（引擎按它裁剪工具集） */
    private static volatile String stubUserRole;
    /** T44：POST 到引擎的请求体（确认结果事实） */
    private static volatile String stubRequestBody;
    private static volatile int stubStatus;
    private static volatile String stubContentType;
    private static volatile String stubBody;
    private static volatile String stubLocation;

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
        stubCustomToken = null;
        stubCookie = null;
        stubAccept = null;
        stubUserRole = null;
        stubRequestBody = null;
        stubStatus = 200;
        stubContentType = "text/event-stream; charset=utf-8";
        stubBody = "";
        stubLocation = null;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        stubMethod = exchange.getRequestMethod();
        stubUri = exchange.getRequestURI().toString();
        stubApiKey = exchange.getRequestHeaders().getFirst("X-Internal-Api-Key");
        stubSatoken = exchange.getRequestHeaders().getFirst("satoken");
        stubCustomToken = exchange.getRequestHeaders().getFirst("custom-token");
        stubCookie = exchange.getRequestHeaders().getFirst("Cookie");
        stubAccept = exchange.getRequestHeaders().getFirst("Accept");
        stubUserRole = exchange.getRequestHeaders().getFirst("X-User-Role");
        stubRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] body = stubBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", stubContentType);
        if (stubLocation != null) {
            exchange.getResponseHeaders().add("Location", stubLocation);
        }
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
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
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
        assertThat(stubUserRole).as("T17：角色随凭据一起透传，引擎按它裁剪工具集")
                .isEqualTo(UserConstant.DEFAULT_ROLE);
    }

    /** T27.6 确认门：confirmation_required 帧对代理是陌生事件类型，必须逐字透传（协议三处同步的代理侧断言） */
    @Test
    void relaysConfirmationRequiredFramesVerbatim() {
        stubBody = "data:{\"event\":\"confirmation_required\",\"operationType\":\"batch_edit\","
                + "\"operationId\":\"op-1\",\"digest\":\"d1\",\"summary\":[\"目标空间 id：30\",\"图片：2 张\"]}\n\n"
                + "data:[DONE]\n\n";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).containsExactly(
                "{\"event\":\"confirmation_required\",\"operationType\":\"batch_edit\","
                        + "\"operationId\":\"op-1\",\"digest\":\"d1\",\"summary\":[\"目标空间 id：30\",\"图片：2 张\"]}",
                "[DONE]");
    }

    /** T44：确认执行结果回灌引擎——POST 到内部端点，带内部密钥，body 是模型面说明 JSON */
    @Test
    void recordsOperationResultToEngineWithInternalKeyAndJsonBody() {
        RecordingManager manager = manager();

        manager.recordOperationResult(123L, "chat-1", "用户已确认并执行批量编辑：提交 2 张。");

        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(stubMethod).isEqualTo("POST");
        assertThat(stubUri).isEqualTo("/api/ai/huoshan/conversations/chat-1/operation-results?userId=123");
        assertThat(stubApiKey).isEqualTo(API_KEY);
        assertThat(stubRequestBody)
                .contains("\"content\"")
                .contains("用户已确认并执行批量编辑：提交 2 张。");
    }

    /** 回灌是旁路：引擎不可达时必须吞掉失败，不把异常抛给调用方（confirm 的返回不受影响） */
    @Test
    void recordOperationResultSwallowsEngineFailure() {
        RecordingManager manager = new RecordingManager(properties("http://127.0.0.1:1/api", API_KEY), executor);

        manager.recordOperationResult(123L, "chat-1", "已执行");
    }

    /** 引擎地址/密钥未配置时抛错（由控制器侧兜住）：记录契约仍是"未配置即拒"，不做静默丢弃 */
    @Test
    void recordOperationResultFailsClosedWhenKeyMissing() {
        RecordingManager manager = new RecordingManager(properties(engineBaseUrl, "  "), executor);

        assertThatThrownBy(() -> manager.recordOperationResult(123L, "chat-1", "已执行"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void forwardsConfiguredCredentialNamesInsteadOfHardCodedDefaults() {
        stubBody = "data:[DONE]\n\n";
        RecordingManager manager = new RecordingManager(
                properties(engineBaseUrl, API_KEY), executor, "custom-token", "CUSTOM_SESSION");

        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        manager.emitter.awaitFrames();

        assertThat(stubCustomToken).isEqualTo(SATOKEN);
        assertThat(stubSatoken).isNull();
        assertThat(stubCookie).isEqualTo("CUSTOM_SESSION=" + SESSION_ID);
    }
    /** T17：管理员角色也要如实到引擎——引擎据此才挂得上看图打标工具 */
    @Test
    void forwardsAdminRoleHeaderSoEngineCanMountTaggingTool() {
        stubBody = "data:[DONE]\n\n";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.ADMIN_ROLE);
        manager.emitter.awaitFrames();

        assertThat(stubUserRole).isEqualTo(UserConstant.ADMIN_ROLE);
    }

    /** 角色为空时不发这个头：引擎按"非管理员"处理（fail-closed），比发一个空值更明确 */
    @Test
    void omitsRoleHeaderWhenRoleIsBlank() {
        stubBody = "data:[DONE]\n\n";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, "   ");
        manager.emitter.awaitFrames();

        assertThat(stubUserRole).isNull();
    }

    @Test
    void refusesUpstreamRedirectInsteadOfForwardingCredentialsAgain() {
        stubStatus = 302;
        stubContentType = "text/plain";
        stubLocation = engineBaseUrl + "/redirect-target";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(REQUESTS.get()).as("代理不得跟随携带内部密钥与用户令牌的重定向").isEqualTo(1);
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).contains("\"event\":\"error\"").contains("助手响应异常");
        assertThat(frames.get(1)).isEqualTo("[DONE]");
    }
    @Test
    void relaysEngineErrorFrameVerbatimWithoutSynthesizingAnotherOne() {
        // T8-c：引擎的失败收尾是 event=error 帧（超时/调用失败）。代理不认识它，
        // 但也绝不改写：原样转发 + [DONE] 收尾，不得再合成一条 answer（否则同一件事说两遍）
        stubBody = "data:{\"event\":\"error\",\"content\":\"助手响应超时，请重试\"}\n\n"
                + "data:[DONE]\n\n";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).containsExactly(
                "{\"event\":\"error\",\"content\":\"助手响应超时，请重试\"}",
                "[DONE]");
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void omitsChatIdWhenBlank() {
        stubBody = "data:[DONE]\n\n";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, null, SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
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
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).containsExactly(
                "{\"event\":\"answer\",\"content\":\"a\"}",
                "{\"event\":\"answer\",\"content\":\"b\"}",
                "[DONE]");
    }

    @Test
    void upstreamUnauthorizedErrorBecomesVisibleError() {
        stubStatus = 401;
        stubContentType = "application/json;charset=UTF-8";
        stubBody = "{\"code\":40100,\"data\":null,\"message\":\"内部接口未授权：缺少或错误的 X-Internal-Api-Key\"}";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).contains("\"event\":\"error\"").contains("内部接口未授权");
        assertThat(frames.get(1)).isEqualTo("[DONE]");
    }

    @Test
    void upstreamJsonErrorWithOkStatusAlsoBecomesVisibleError() {
        stubStatus = 200;
        stubContentType = "application/json";
        stubBody = "{\"code\":50000,\"data\":null,\"message\":\"图库助手模型未配置：请设置 app.ai.openai.assistant.api-key 与 model\"}";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).contains("\"event\":\"error\"").contains("图库助手模型未配置");
        assertThat(frames.get(1)).isEqualTo("[DONE]");
    }

    /**
     * 上游回 200 + 非 JSON 体（典型是网关的 HTML 错误页）：**原文不得进用户可见的文案**——
     * 用户只该看到通用文案，细节走日志。这是 2026-09-15 复核提升的 P2。
     */
    @Test
    void nonJsonUpstreamErrorBodyDoesNotLeakToTheUser() {
        stubStatus = 200;
        stubContentType = "text/html";
        stubBody = "<html><head><title>502 Bad Gateway</title></head><body>"
                + "<center><h1>502 Bad Gateway</h1></center><hr><center>nginx/1.24.0</center></body></html>";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0))
                .contains("\"event\":\"error\"")
                .contains("助手响应异常")
                .as("非 JSON 的上游错误体不得把原文当文案发给用户")
                .doesNotContain("Bad Gateway")
                .doesNotContain("nginx")
                .doesNotContain("<html>");
        assertThat(frames.get(1)).isEqualTo("[DONE]");
    }

    @Test
    void unreachableEngineBecomesVisibleError() throws IOException {        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        RecordingManager manager = new RecordingManager(
                properties("http://127.0.0.1:" + closedPort + "/api", API_KEY), executor);
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).contains("\"event\":\"error\"").contains("暂时不可用");
        assertThat(frames.get(1)).isEqualTo("[DONE]");
    }

    @Test
    void interruptedStreamBeforeDoneStillEndsWithVisibleError() {
        // 中继中断（流断在 [DONE] 之前）：已中继的回答保留，再补一条 error 帧 + [DONE] 收尾（T39）——
        // 已知限制：此时"答案 + 错误同屏"，语义与 T39 前一致，只是错误不再伪装成 answer
        stubBody = "data:{\"event\":\"answer\",\"content\":\"说了一半\"}\n\n";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).containsExactly(
                "{\"event\":\"answer\",\"content\":\"说了一半\"}",
                "{\"event\":\"error\",\"content\":\"图库助手响应意外中断，请重试\"}",
                "[DONE]");
    }

    @Test
    void failsClosedWhenEngineKeyMissing() {
        RecordingManager manager = new RecordingManager(properties(engineBaseUrl, " "), executor);

        assertThatThrownBy(() -> manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID,
                UserConstant.DEFAULT_ROLE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("引擎密钥未配置")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
        assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void failsClosedWhenEngineUrlMissing() {
        RecordingManager manager = new RecordingManager(properties("", API_KEY), executor);

        assertThatThrownBy(() -> manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID,
                UserConstant.DEFAULT_ROLE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("引擎地址未配置");
        assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void reportsFailureLoudlyWhenExecutorIsSaturated() {
        RecordingManager manager = new RecordingManager(properties(engineBaseUrl, API_KEY), new RejectingExecutor());

        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);

        assertThat(manager.emitter.awaitFrames()).isEmpty();
        assertThat(manager.emitter.failed).isTrue();
        assertThat(REQUESTS.get()).isZero();
    }

    /**
     * R4（T27.2）：客户端在 worker 启动前就断开（超时/断流）时，代理不得再建连、更不得发起上游请求——
     * 旧实现里取消动作只关"已登记"的连接，此刻登记还是空的，worker 随后照常跑完一整轮（白烧 LLM 与上游负载）。
     */
    @Test
    void cancelBeforeWorkerStartsSkipsUpstreamEntirely() {
        stubBody = "data:[DONE]\n\n";
        GatedExecutor gated = new GatedExecutor();
        ControllableEmitter emitter = new ControllableEmitter();
        RecordingManager manager = new RecordingManager(properties(engineBaseUrl, API_KEY), gated, emitter);

        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        emitter.triggerTimeout();
        gated.releaseAndAwait();

        assertThat(REQUESTS.get()).as("客户端已断开：不得再向上游发起请求").isZero();
        assertThat(emitter.framesSnapshot()).as("客户端已断开：不该再产生任何帧").isEmpty();
    }

    /**
     * R7（T27.2）：单行长错误体的尾部不得进用户可见文案。旧实现用 {@code readLine()} 整行读进内存，
     * 长度检查只挡住"下一行"，于是 8000 字符的单行体被原样取 message 发进 error 帧。
     */
    @Test
    void overlongSingleLineErrorBodyIsBoundedAndTailNeverReachesTheUser() {
        stubStatus = 200;
        stubContentType = "application/json";
        stubBody = "{\"message\":\"" + "A".repeat(8000) + "TAIL-MARKER\"}";

        RecordingManager manager = manager();
        manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
        List<String> frames = manager.emitter.awaitFrames();

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0))
                .contains("\"event\":\"error\"")
                .as("错误体必须按上限有界读取，尾部 marker 不得出现在用户文案里")
                .doesNotContain("TAIL-MARKER");
        assertThat(frames.get(1)).isEqualTo("[DONE]");
    }

    /**
     * R7（T27.2）：错误体原文进日志前要去掉控制字符——\u000B 这类字符在日志查看器里会被当换行渲染，
     * 等于让上游用一段错误体伪造日志行；ANSI 转义（\u001b）还会污染终端。
     */
    @Test
    void errorBodyControlCharactersNeverReachTheLog() {
        stubStatus = 200;
        stubContentType = "text/html";
        stubBody = "<html>gateway\u000BFORGED-LOG-LINE\u001b[31m</html>";

        Logger logger = (Logger) LoggerFactory.getLogger(AiAssistantProxyManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RecordingManager manager = manager();
            manager.chat(MESSAGE, 123L, "chat-1", SATOKEN, SESSION_ID, UserConstant.DEFAULT_ROLE);
            manager.emitter.awaitFrames();
        } finally {
            logger.detachAppender(appender);
        }

        List<String> logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logs).as("这条用例必须确实走到「原文进日志」的分支").anyMatch(message -> message.contains("原文"));
        assertThat(logs).as("日志里不得残留任何控制字符")
                .allMatch(message -> message.chars().noneMatch(c -> c == 0x0B || c == 0x1B));
    }

    /**
     * R4（T27.2）"取消落在建连与登记之间"的时序：worker 把刚建好的连接交给 register 时，
     * 取消已经发生 ⇒ register 必须立刻断开并回 false，worker 不得再发请求；断开只做一次（幂等）。
     */
    @Test
    void registerAfterCancelDisconnectsImmediatelyAndRefusesToProceed() {
        AiAssistantProxyManager.Upstream upstream = new AiAssistantProxyManager.Upstream();
        RecordingConnection connection = new RecordingConnection();

        upstream.cancel();

        assertThat(upstream.register(connection)).as("取消已发生：worker 不得再进入请求/中继").isFalse();
        assertThat(connection.disconnects.get()).isEqualTo(1);
    }

    /** R4（T27.2）"取消晚于登记"的时序：取消侧必须断开已登记的那条连接 */
    @Test
    void cancelAfterRegisterDisconnectsTheRegisteredConnection() {
        AiAssistantProxyManager.Upstream upstream = new AiAssistantProxyManager.Upstream();
        RecordingConnection connection = new RecordingConnection();

        assertThat(upstream.register(connection)).isTrue();
        upstream.cancel();
        upstream.disconnect();

        assertThat(connection.disconnects.get()).as("重复断开不得重复生效").isEqualTo(1);
    }

    @Test
    void fetchMessagesCarriesInternalKeyAndRelaysBody() throws IOException {
        // T37：读历史走同一内部密钥信道，但**不带用户凭据组**（引擎读消息不碰图库）
        stubContentType = "application/json";
        stubBody = "{\"code\":0,\"data\":[{\"id\":\"1\",\"role\":\"user\",\"content\":\"hi\"}]}";

        RecordingManager manager = manager();
        String body = manager.fetchConversationMessages(123L, "chat-1");

        assertThat(body).as("引擎响应体原文逐字透传，代理不重新包装").isEqualTo(stubBody);
        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(stubMethod).isEqualTo("GET");
        assertThat(stubUri).startsWith("/api/ai/huoshan/conversations/chat-1/messages");
        assertThat(queryOf(stubUri)).containsEntry("userId", "123");
        assertThat(stubApiKey).as("读历史与 /chat 同一内部密钥信道").isEqualTo(API_KEY);
        assertThat(stubSatoken).as("读历史不转发用户 satoken").isNull();
        assertThat(stubCookie).as("读历史不转发用户会话 Cookie").isNull();
    }

    @Test
    void fetchMessagesThrowsOnNon200() {
        stubStatus = 500;
        stubContentType = "application/json";
        stubBody = "{\"code\":50000,\"message\":\"boom\"}";

        RecordingManager manager = manager();
        assertThatThrownBy(() -> manager.fetchConversationMessages(123L, "chat-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("助手暂时不可用")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
    }

    @Test
    void fetchMessagesThrowsWhenEngineUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        RecordingManager manager = new RecordingManager(
                properties("http://127.0.0.1:" + closedPort + "/api", API_KEY), executor);

        assertThatThrownBy(() -> manager.fetchConversationMessages(123L, "chat-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("助手暂时不可用")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
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

        List<String> framesSnapshot() {
            synchronized (frames) {
                return new ArrayList<>(frames);
            }
        }
    }

    /**
     * 受控 emitter（T27.2）：把 onCompletion/onError/onTimeout 的回调收下来，
     * 让测试能精确挑一个时序触发"客户端断开"——真实路径由 servlet 容器在断流/超时时回调。
     */
    private static class ControllableEmitter extends RecordingSseEmitter {

        private Runnable completionCallback;
        private Consumer<Throwable> errorCallback;
        private Runnable timeoutCallback;

        @Override
        public void onCompletion(Runnable callback) {
            this.completionCallback = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            this.errorCallback = callback;
        }

        @Override
        public void onTimeout(Runnable callback) {
            this.timeoutCallback = callback;
        }

        void triggerTimeout() {
            timeoutCallback.run();
        }
    }

    /** 假连接：只记 disconnect() 次数（HttpURLConnection 的抽象壳，不连任何东西） */
    private static class RecordingConnection extends HttpURLConnection {

        private final AtomicInteger disconnects = new AtomicInteger();

        RecordingConnection() {
            super((URL) null);
        }

        @Override
        public void disconnect() {
            disconnects.incrementAndGet();
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }

    /** 门控执行器：把提交的任务扣住不放，直到 {@link #releaseAndAwait()}——用于构造"取消早于 worker 启动"的时序 */
    private static class GatedExecutor extends java.util.concurrent.AbstractExecutorService {

        private volatile Runnable pending;

        @Override
        public void execute(Runnable command) {
            this.pending = command;
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

        void releaseAndAwait() {
            Runnable command = pending;
            assertThat(command).as("worker 应已被提交到执行器").isNotNull();
            Thread worker = new Thread(command, "gated-proxy-worker");
            worker.setDaemon(true);
            worker.start();
            try {
                worker.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待门控 worker 结束被中断", e);
            }
        }
    }

    /** 覆写 createEmitter 注入记录型 emitter；其余行为与生产一致 */
    private static class RecordingManager extends AiAssistantProxyManager {

        private final RecordingSseEmitter emitter;

        RecordingManager(AiAssistantProperties properties, ExecutorService executor) {
            this(properties, executor, new RecordingSseEmitter());
        }

        RecordingManager(AiAssistantProperties properties, ExecutorService executor, RecordingSseEmitter emitter) {
            this(properties, executor, emitter, "satoken", "SESSION");
        }

        RecordingManager(AiAssistantProperties properties, ExecutorService executor,
                         String tokenName, String sessionCookieName) {
            this(properties, executor, new RecordingSseEmitter(), tokenName, sessionCookieName);
        }

        private RecordingManager(AiAssistantProperties properties, ExecutorService executor,
                                 RecordingSseEmitter emitter, String tokenName, String sessionCookieName) {
            super(properties, new ObjectMapper(), executor, tokenName, sessionCookieName);
            this.emitter = emitter;
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
