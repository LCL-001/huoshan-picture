package com.lcl.yunpicturebackend.api.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 看图接口单测（T15）：请求形态（路径 / Bearer 鉴权 / model / 文本+图片两段 content）、响应解析、
 * 以及"厂商原文不进面向调用方的文案"。桩是进程内 JDK HttpServer，不依赖外部服务，进门禁。
 */
@DisplayName("看图接口（OpenAI 兼容）")
class AiVisionTagApiTest {

    private static final String API_KEY = "vision-key-1";
    private static final String MODEL = "mimo-v2.5";
    private static final String IMAGE_URL = "https://cos.example.com/9.jpg";
    private static final String PROMPT = "看图打标";

    private static HttpServer server;
    private static String baseUrl;
    private static volatile String stubMethod;
    private static volatile String stubUri;
    private static volatile String stubAuth;
    private static volatile String stubContentType;
    private static volatile String stubBody;
    private static volatile int stubStatus;
    private static volatile String stubResponse;
    private static final AtomicInteger REQUESTS = new AtomicInteger();

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", AiVisionTagApiTest::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @BeforeEach
    void resetStub() {
        REQUESTS.set(0);
        stubMethod = null;
        stubUri = null;
        stubAuth = null;
        stubContentType = null;
        stubBody = null;
        stubStatus = 200;
        stubResponse = "{\"choices\":[{\"message\":{\"content\":\"{\\\"tags\\\":[\\\"高清\\\"]}\"}}]}";
    }

    private static void handle(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        stubMethod = exchange.getRequestMethod();
        stubUri = exchange.getRequestURI().toString();
        stubAuth = exchange.getRequestHeaders().getFirst("Authorization");
        stubContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        stubBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] body = stubResponse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(stubStatus, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private AiVisionTagApi api() {
        AiVisionTagApi api = new AiVisionTagApi(new ObjectMapper());
        ReflectionTestUtils.setField(api, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(api, "apiKey", API_KEY);
        ReflectionTestUtils.setField(api, "model", MODEL);
        ReflectionTestUtils.setField(api, "timeoutMs", 5_000);
        return api;
    }

    @Test
    @DisplayName("请求形态：/v1/chat/completions + Bearer + model + 文本与 image_url 两段 content")
    void sendsOpenAiCompatibleRequest() throws Exception {
        String content = api().describeImage(IMAGE_URL, PROMPT);

        assertThat(content).contains("高清");
        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(stubMethod).isEqualTo("POST");
        assertThat(stubUri).isEqualTo("/v1/chat/completions");
        assertThat(stubAuth).isEqualTo("Bearer " + API_KEY);
        assertThat(stubContentType).contains("application/json");
        JsonNode body = new ObjectMapper().readTree(stubBody);
        assertThat(body.get("model").asText()).isEqualTo(MODEL);
        JsonNode parts = body.path("messages").path(0).path("content");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).path("type").asText()).isEqualTo("text");
        assertThat(parts.get(0).path("text").asText()).isEqualTo(PROMPT);
        assertThat(parts.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(parts.get(1).path("image_url").path("url").asText()).isEqualTo(IMAGE_URL);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    @DisplayName("非 2xx：抛业务异常，文案只带状态码、不带厂商响应体原文")
    void reportsFailureWithoutVendorText() {
        stubStatus = 401;
        stubResponse = "{\"error\":{\"message\":\"SECRET-VENDOR-DETAIL: invalid api key sk-xxx\"}}";

        assertThatThrownBy(() -> api().describeImage(IMAGE_URL, PROMPT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("SECRET-VENDOR-DETAIL");
    }

    @Test
    @DisplayName("响应缺少 content：抛业务异常（不返回空建议）")
    void failsWhenContentMissing() {
        stubResponse = "{\"choices\":[{\"message\":{}}]}";

        assertThatThrownBy(() -> api().describeImage(IMAGE_URL, PROMPT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少 content");
    }

    @Test
    @DisplayName("未配置模型：直接拒，且一个请求都不发（fail-closed）")
    void failsClosedWhenNotConfigured() {
        AiVisionTagApi api = new AiVisionTagApi(new ObjectMapper());
        ReflectionTestUtils.setField(api, "baseUrl", "");
        ReflectionTestUtils.setField(api, "apiKey", "");
        ReflectionTestUtils.setField(api, "model", "");

        assertThat(api.isConfigured()).isFalse();
        assertThatThrownBy(() -> api.describeImage(IMAGE_URL, PROMPT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 打标模型未配置");
        assertThat(REQUESTS.get()).isZero();
    }
}
