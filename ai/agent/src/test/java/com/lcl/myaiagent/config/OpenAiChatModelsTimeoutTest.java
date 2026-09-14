package com.lcl.myaiagent.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import reactor.core.Disposable;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6.1 流式路径超时单测（docs/plan.md T6.1）：三处接线各自钉住——
 * 阻塞路径 RestClient 的 requestFactory 超时、流式路径 WebClient connector 的连接与响应头超时，
 * 以及"吐了几段之后静默"必须由响应体流上的空闲超时兜住（review 实测只给 connector 接超时抓不到这种形态，
 * 因为 connector 的读超时等价于 JDK 的请求超时，只覆盖到响应头到达）。
 * <p>
 * 桩服务器是进程内的 JDK HttpServer，不依赖外部服务，随测试类启停。
 * </p>
 */
class OpenAiChatModelsTimeoutTest {

    /** 失败形态用的超时：桩服务器要么不响应、要么吐两段就静默，须在超时值附近失败 */
    private static final Duration TIMEOUT = Duration.ofMillis(400);

    /** 稳态流用的超时：6 段 × 300ms 共约 1.8s，总时长超过超时值（证明它不是整响应死线），段间隔又远小于它（不会被误杀） */
    private static final Duration CONTROL_TIMEOUT = Duration.ofMillis(1000);

    private static final int CONTROL_CHUNKS = 6;

    /** 等待上限：远大于超时值，又远小于桩服务器 60s 的静默 */
    private static final long WAIT_SECONDS = 5L;

    private static HttpServer stubServer;

    private static String baseUrl;

    @BeforeAll
    static void startStubServer() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/", OpenAiChatModelsTimeoutTest::handle);
        stubServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-openai-server");
            thread.setDaemon(true);
            return thread;
        }));
        stubServer.start();
        baseUrl = "http://127.0.0.1:" + stubServer.getAddress().getPort();
    }

    @AfterAll
    static void stopStubServer() {
        stubServer.stop(0);
    }

    @Test
    void blockingPathHasConnectAndReadTimeoutOnRequestFactory() throws Exception {
        Object restClient = readField(readField(assistant(baseUrl + "/steady", TIMEOUT), "openAiApi"), "restClient");

        Object requestFactory = readField(restClient, "clientRequestFactory");

        assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat((int) readField(requestFactory, "connectTimeout")).isEqualTo((int) TIMEOUT.toMillis());
        assertThat((int) readField(requestFactory, "readTimeout")).isEqualTo((int) TIMEOUT.toMillis());
    }

    @Test
    void streamingConnectorHasConnectAndReadTimeoutOnUnderlyingHttpClient() throws Exception {
        ClientHttpConnector connector = OpenAiChatModels.streamingConnector(TIMEOUT);

        assertThat(connector).isInstanceOf(JdkClientHttpConnector.class);
        assertThat((Duration) readField(connector, "readTimeout")).isEqualTo(TIMEOUT);
        HttpClient httpClient = (HttpClient) readField(connector, "httpClient");
        assertThat(httpClient.connectTimeout()).contains(TIMEOUT);
    }

    @Test
    void streamFailsWhenEndpointNeverResponds() {
        // 连响应头都不来：connector 的读超时负责（此时还没有响应体，空闲超时无从计时）
        assertTimeoutFailure(awaitStreamFailure(assistant(baseUrl + "/hang", TIMEOUT)), "首包不来");
    }

    @Test
    void streamFailsWhenStreamGoesSilentMidway() {
        // 吐了两段后静默：connector 的读超时抓不到，必须由响应体流上的空闲超时兜住
        assertTimeoutFailure(awaitStreamFailure(assistant(baseUrl + "/silent", TIMEOUT)), "流中途静默");
    }

    @Test
    void streamCompletesWhileChunksKeepArriving() {
        List<String> received = new ArrayList<>();
        CompletableFuture<String> outcome = new CompletableFuture<>();
        Disposable subscription = assistant(baseUrl + "/steady", CONTROL_TIMEOUT).stream(new Prompt("hi"))
                .subscribe(response -> received.add(response.getResult().getOutput().getText()),
                        outcome::completeExceptionally,
                        () -> outcome.complete("done"));
        try {
            assertThat(await(outcome)).as("稳态长流被误杀：%s", awaitMessage(outcome)).isEqualTo("done");
        } finally {
            subscription.dispose();
        }
        assertThat(received).hasSize(CONTROL_CHUNKS);
    }

    @Test
    void blockingCallFailsWhenEndpointNeverResponds() {
        ChatModel model = assistant(baseUrl + "/hang", TIMEOUT);
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        Thread caller = new Thread(() -> {
            try {
                model.call(new Prompt("hi"));
                outcome.complete(null);
            } catch (Throwable error) {
                outcome.complete(error);
            }
        }, "blocking-call");
        caller.setDaemon(true);
        caller.start();

        assertTimeoutFailure(awaitOutcome(outcome), "阻塞路径无响应");
    }

    private static ChatModel assistant(String baseUrl, Duration timeout) {
        OpenAiModelProperties properties = new OpenAiModelProperties();
        properties.getAssistant().setBaseUrl(baseUrl);
        properties.getAssistant().setApiKey("test-key");
        properties.getAssistant().setModel("test-model");
        properties.getAssistant().setTimeout(timeout);
        return OpenAiChatModels.from(properties).assistant();
    }

    private static Throwable awaitStreamFailure(ChatModel model) {
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        Disposable subscription = model.stream(new Prompt("hi"))
                .subscribe(response -> {
                }, outcome::complete, () -> outcome.complete(null));
        try {
            return awaitOutcome(outcome);
        } finally {
            subscription.dispose();
        }
    }

    /** 取流（或调用）的失败原因；超时形态在等待上限内一直没有任何信号时返回 null，代表"超时未生效" */
    private static Throwable awaitOutcome(CompletableFuture<Throwable> outcome) {
        try {
            return outcome.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static void assertTimeoutFailure(Throwable error, String shape) {
        assertThat(error).as("%s：等待 %ds 仍未收到任何信号，超时未生效", shape, WAIT_SECONDS).isNotNull();
        assertThat(causeChain(error))
                .as("%s：失败链里应有超时异常，实际=%s", shape, causeChain(error))
                .anyMatch(cause -> cause instanceof TimeoutException
                        || cause instanceof HttpTimeoutException
                        || cause instanceof SocketTimeoutException);
    }

    private static List<Throwable> causeChain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    private static String awaitMessage(CompletableFuture<String> outcome) {
        if (!outcome.isDone()) {
            return "等待 " + WAIT_SECONDS + "s 无结果";
        }
        try {
            outcome.getNow("?");
        } catch (Exception e) {
            return causeChain(e).toString();
        }
        return "done";
    }

    private static <T> T await(CompletableFuture<T> outcome) {
        try {
            return outcome.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("等待 " + WAIT_SECONDS + "s 未拿到结果：" + causeChain(e), e);
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 字段可能在父类上，继续往上找
            }
        }
        throw new IllegalStateException("找不到字段 " + name + "：" + target.getClass().getName()
                + "（框架内部结构可能已变，本测试的反射路径需要同步更新）");
    }

    private static void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            if (path.contains("/steady")) {
                drip(exchange, CONTROL_CHUNKS, false);
            } else if (path.contains("/silent")) {
                drip(exchange, 2, true);
            } else {
                // 完全不响应：连响应头都不发
                sleep(60_000L);
            }
        } catch (IOException ignored) {
            // 客户端按超时主动断开是预期路径，不是服务端错误
        } finally {
            exchange.close();
        }
    }

    private static void drip(HttpExchange exchange, int chunks, boolean thenSilence) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream body = exchange.getResponseBody();
        for (int i = 0; i < chunks; i++) {
            String json = "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stub\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + i + "\"},\"finish_reason\":null}]}";
            body.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            body.flush();
            sleep(300L);
        }
        if (thenSilence) {
            sleep(60_000L);
            return;
        }
        body.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        body.flush();
        body.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
