package com.lcl.myaiagent.tools.huoshan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 档 3 看图打标单测（docs/plan.md T7）：`visionTagger` 的取值链（图片 id → 图库取地址 → 多模态模型）
 * 与返回契约，用**假 ChatModel**（不连外网）+ 进程内桩 HTTP（不连图库）。
 * <p>
 * 重点守护四件事：① 提示词里必须带上当前词表（"优先复用已有词"的前提，设计文档 L63）；
 * ② 图片是作为**图片内容**（media）送进模型的，不是把 URL 当文本喂，且 MIME 跟着后缀走；
 * ③ 某一张失败只降级那一张，其余张照常处理；④ 登录态失效则提前停止并如实报"剩余未尝试"。
 * </p>
 */
class VisionTaggerToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 桩路由：**含 query 的请求 URI** → 响应体（如 /picture/get/vo?id=7） */
    private static final Map<String, String> ROUTES = new ConcurrentHashMap<>();

    private static final List<String> REQUEST_URIS = Collections.synchronizedList(new ArrayList<>());

    private static HttpServer stubServer;

    private static String baseUrl;

    private static final String VOCABULARY = """
            {"code":0,"data":{"tagList":["热门","风景"],"categoryList":["模板","电商"]},"message":"ok"}""";

    @BeforeAll
    static void startStubServer() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/", VisionTaggerToolTest::handle);
        stubServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-huoshan-vision-server");
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

    @BeforeEach
    void resetStub() {
        ROUTES.clear();
        REQUEST_URIS.clear();
        ROUTES.put("/picture/tag_category", VOCABULARY);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        REQUEST_URIS.add(uri);
        String body = ROUTES.getOrDefault(uri, """
                {"code":40400,"data":null,"message":"桩未配置该 URI"}""");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static HuoshanApiClient client() {
        return new HuoshanApiClient(baseUrl, "test-satoken", "test-session",
                Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    private static void routePicture(String pictureId, String url) {
        ROUTES.put("/picture/get/vo?id=" + pictureId, """
                {"code":0,"data":{"id":"%s","name":"落日.jpg","url":"%s","spaceId":"100"},"message":"ok"}"""
                .formatted(pictureId, url));
    }

    /** 假视觉模型：按顺序吐预置回复，并记录收到的 Prompt */
    private static final class StubVisionModel implements ChatModel {

        private final Deque<String> replies = new ArrayDeque<>();

        private final List<Prompt> prompts = new ArrayList<>();

        StubVisionModel reply(String... replies) {
            this.replies.addAll(List.of(replies));
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompts.add(prompt);
            String reply = this.replies.poll();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply == null ? "" : reply))));
        }
    }

    private static UserMessage userMessageOf(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("提示里没有 UserMessage：" + prompt.getInstructions()));
    }

    @Test
    void tagsPictureAndInjectsVocabularyIntoThePrompt() throws Exception {
        routePicture("2045431671356391425", "https://cos/1.jpg");
        StubVisionModel model = new StubVisionModel().reply("""
                {"tags":["风景","热门"],"category":"模板"}""");

        JsonNode result = MAPPER.readTree(new VisionTaggerTool(client(), model)
                .visionTagger("100", List.of("2045431671356391425")));

        assertThat(REQUEST_URIS)
                .as("先取词表、再按 id 取图片地址")
                .containsExactly("/picture/tag_category", "/picture/get/vo?id=2045431671356391425");
        assertThat(result.get("requested").asInt()).isEqualTo(1);
        assertThat(result.get("suggested").asInt()).isEqualTo(1);
        assertThat(result.get("skipped").asInt()).isZero();
        JsonNode suggestion = result.get("suggestions").get(0);
        assertThat(suggestion.get("pictureId").asText()).isEqualTo("2045431671356391425");
        assertThat(suggestion.get("ok").asBoolean()).isTrue();
        assertThat(suggestion.get("tags")).hasSize(2);
        assertThat(suggestion.get("category").asText()).isEqualTo("模板");
        assertThat(suggestion.get("url").asText()).isEqualTo("https://cos/1.jpg");

        assertThat(model.prompts).hasSize(1);
        UserMessage prompt = userMessageOf(model.prompts.get(0));
        assertThat(prompt.getText())
                .as("词表必须进提示词：优先复用已有词是设计文档 L63 的硬要求")
                .contains("热门").contains("风景").contains("模板").contains("电商");
        assertThat(prompt.getMedia()).as("图片必须以图片内容送进模型，而不是把 URL 当文本").hasSize(1);
        assertThat(prompt.getMedia().get(0).getData().toString())
                .as("送进模型的必须是这张图的地址")
                .contains("https://cos/1.jpg");
        assertThat(result.get("note").asText())
                .as("建议不得被当成本次已落库")
                .contains("尚未写入图库");
    }

    @Test
    void messageTypeFollowsThePictureSuffix() throws Exception {
        routePicture("7", "https://cos/3.webp");
        StubVisionModel model = new StubVisionModel().reply("""
                {"tags":["风景"],"category":""}""");

        new VisionTaggerTool(client(), model).visionTagger("100", List.of("7"));

        assertThat(userMessageOf(model.prompts.get(0)).getMedia().get(0).getMimeType().toString())
                .as("webp 图必须按 webp 发，不能一律当 jpeg")
                .isEqualTo("image/webp");
    }

    @Test
    void toleratesMarkdownFencedResponse() throws Exception {
        routePicture("7", "https://cos/2.png");
        StubVisionModel model = new StubVisionModel().reply("""
                ```json
                {"tags":["风景"],"category":"电商"}
                ```
                """);

        JsonNode result = MAPPER.readTree(new VisionTaggerTool(client(), model).visionTagger("100", List.of("7")));

        JsonNode suggestion = result.get("suggestions").get(0);
        assertThat(suggestion.get("ok").asBoolean()).isTrue();
        assertThat(suggestion.get("tags").get(0).asText()).isEqualTo("风景");
        assertThat(suggestion.get("category").asText()).isEqualTo("电商");
    }

    @Test
    void unparsableModelReplyIsReportedPerPictureInsteadOfThrowing() throws Exception {
        routePicture("7", "https://cos/2.png");
        StubVisionModel model = new StubVisionModel().reply("我觉得这张图挺好看的");

        JsonNode result = MAPPER.readTree(new VisionTaggerTool(client(), model).visionTagger("100", List.of("7")));

        JsonNode suggestion = result.get("suggestions").get(0);
        assertThat(suggestion.get("ok").asBoolean()).isFalse();
        assertThat(suggestion.get("message").asText()).contains("无法解析");
        assertThat(result.get("suggested").asInt()).isZero();
    }

    @Test
    void oneBadPictureDoesNotSpoilTheOthers() throws Exception {
        // 第一张没有可访问地址（降级），第二张正常打标
        ROUTES.put("/picture/get/vo?id=7", """
                {"code":0,"data":{"id":"7","name":"无地址.jpg","url":null},"message":"ok"}""");
        routePicture("8", "https://cos/8.jpg");
        StubVisionModel model = new StubVisionModel().reply("""
                {"tags":["风景"],"category":"模板"}""");

        JsonNode result = MAPPER.readTree(new VisionTaggerTool(client(), model).visionTagger("100", List.of("7", "8")));

        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("suggested").asInt()).as("只有第二张拿到了建议").isEqualTo(1);
        JsonNode first = result.get("suggestions").get(0);
        JsonNode second = result.get("suggestions").get(1);
        assertThat(first.get("ok").asBoolean()).isFalse();
        assertThat(first.get("message").asText()).contains("没有可访问的图片地址");
        assertThat(second.get("ok").asBoolean())
                .as("上一张失败不影响继续处理下一张")
                .isTrue();
        assertThat(second.get("pictureId").asText()).isEqualTo("8");
    }

    @Test
    void stopsEarlyWhenLoginStateIsGoneAndReportsSkipped() throws Exception {
        ROUTES.put("/picture/get/vo?id=7", """
                {"code":40100,"data":null,"message":"未登录"}""");

        JsonNode result = MAPPER.readTree(new VisionTaggerTool(client(), new StubVisionModel())
                .visionTagger("100", List.of("7", "8", "9")));

        assertThat(REQUEST_URIS)
                .as("登录态失效后不该继续白跑后面两张")
                .containsExactly("/picture/tag_category", "/picture/get/vo?id=7");
        assertThat(result.get("skipped").asInt()).isEqualTo(2);
        assertThat(result.get("note").asText()).contains("剩余 2 张未尝试");
    }

    @Test
    void rejectsBadArgumentsLocallyWithoutCallingAnything() throws Exception {
        VisionTaggerTool tool = new VisionTaggerTool(client(), new StubVisionModel());

        JsonNode noSpace = MAPPER.readTree(tool.visionTagger(" ", List.of("7")));
        JsonNode noIds = MAPPER.readTree(tool.visionTagger("100", List.of(" ")));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < VisionTaggerTool.MAX_IMAGES_PER_CALL + 1; i++) {
            tooMany.add(String.valueOf(i));
        }
        JsonNode overCap = MAPPER.readTree(tool.visionTagger("100", tooMany));

        assertThat(REQUEST_URIS).as("本地能判出的参数错不该打图库").isEmpty();
        assertThat(noSpace.get("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(noIds.get("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(overCap.get("message").asText()).contains("分批");
    }
}
