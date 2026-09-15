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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 档 3 看图打标单测（docs/plan.md T7）：`visionTagger` 的取值链（图片 id → 图库取地址 → 多模态模型）
 * 与返回契约，用**假 ChatModel**（不连外网）+ 进程内桩 HTTP（不连图库）。
 * <p>
 * 重点守护：① 提示词里必须带上当前词表（"优先复用已有词"的前提，设计文档 L63）；
 * ② 图片是作为**图片内容**（media）送进模型的，不是把 URL 当文本喂，且 MIME 跟着后缀走；
 * ③ 某一张失败只降级那一张，其余张照常处理；④ 登录态失效则停止提交后续批次并如实报"剩余未尝试"。
 * </p>
 * <p>
 * T18 追加一组"层次一"契约（与 backend `PictureAiTagManager` 同口径）：**峰值并发恰为并发度**、
 * **结果按输入顺序**、**单张超时只降级该张**；④ 的粒度相应从"逐张"变成"逐波"（并发下同波几张已在飞）。
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

    /**
     * 并发用假模型（T18）：线程安全，回复**由图片地址推导**（每张的标签固定，便于断言保序），
     * 可给单张注入耗时，并记录峰值并发。
     */
    private static final class ConcurrentVisionModel implements ChatModel {

        private final AtomicInteger inFlight = new AtomicInteger();

        private final AtomicInteger peak = new AtomicInteger();

        private volatile long delayMillis;

        private volatile String slowUrlFragment;

        private volatile long slowDelayMillis;

        ConcurrentVisionModel delay(long millis) {
            this.delayMillis = millis;
            return this;
        }

        ConcurrentVisionModel slowFor(String urlFragment, long millis) {
            this.slowUrlFragment = urlFragment;
            this.slowDelayMillis = millis;
            return this;
        }

        int peak() {
            return peak.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                String url = mediaUrlOf(prompt);
                long sleep = url != null && slowUrlFragment != null && url.contains(slowUrlFragment)
                        ? slowDelayMillis : delayMillis;
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }
                // 标签由文件名推导（7.jpg → t7），这样并发下每张的建议仍是确定的
                String index = url == null ? "?" : url.substring(url.lastIndexOf('/') + 1, url.lastIndexOf('.'));
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"tags\":[\"t" + index + "\"],\"category\":\"c\"}"))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("看图被中断", e);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private static String mediaUrlOf(Prompt prompt) {
            UserMessage message = userMessageOf(prompt);
            return message.getMedia().isEmpty() ? null : message.getMedia().get(0).getData().toString();
        }
    }

    /** T18：有界并发——8 张、并发 4 ⇒ 峰值恰为 4（不是 8 张一起打） */
    @Test
    void looksAtPicturesWithBoundedConcurrency() throws Exception {
        ConcurrentVisionModel model = new ConcurrentVisionModel().delay(200);
        List<String> ids = List.of("7", "8", "9", "10", "11", "12", "13", "14");
        for (String id : ids) {
            routePicture(id, "https://cos/" + id + ".jpg");
        }

        JsonNode result = MAPPER.readTree(new VisionTaggerTool(client(), model).visionTagger("100", ids));

        assertThat(result.get("suggested").asInt()).isEqualTo(8);
        assertThat(model.peak())
                .as("峰值并发应恰好是 4，而不是把 8 张一起打出去")
                .isEqualTo(4)
                .isEqualTo(VisionTaggerTool.CONCURRENCY);
    }

    /** T18：结果保序——第一张最慢（完成顺序与输入顺序相反），返回仍按输入顺序一一对应 */
    @Test
    void returnsSuggestionsInInputOrderEvenWhenLaterPicturesFinishFirst() throws Exception {
        ConcurrentVisionModel model = new ConcurrentVisionModel().slowFor("7.jpg", 400).delay(30);
        List<String> ids = List.of("7", "8", "9");
        for (String id : ids) {
            routePicture(id, "https://cos/" + id + ".jpg");
        }

        JsonNode result = MAPPER.readTree(new VisionTaggerTool(client(), model).visionTagger("100", ids));

        JsonNode suggestions = result.get("suggestions");
        assertThat(suggestions).hasSize(3);
        assertThat(suggestions.get(0).get("pictureId").asText()).as("第一张最慢也要排在第一位").isEqualTo("7");
        assertThat(suggestions.get(0).get("tags").get(0).asText()).isEqualTo("t7");
        assertThat(suggestions.get(1).get("tags").get(0).asText()).isEqualTo("t8");
        assertThat(suggestions.get(2).get("tags").get(0).asText()).isEqualTo("t9");
    }

    /** T18：单张超时只降级那一张（注入 150ms 单张预算，只有慢的那张降级） */
    @Test
    void perPictureTimeoutDegradesOnlyThatPicture() throws Exception {
        ConcurrentVisionModel model = new ConcurrentVisionModel().slowFor("8.jpg", 2_000);
        List<String> ids = List.of("7", "8", "9");
        for (String id : ids) {
            routePicture(id, "https://cos/" + id + ".jpg");
        }

        JsonNode result = MAPPER.readTree(new VisionTaggerTool(client(), model, 4, Duration.ofMillis(150))
                .visionTagger("100", ids));

        JsonNode suggestions = result.get("suggestions");
        assertThat(suggestions).hasSize(3);
        assertThat(suggestions.get(0).get("ok").asBoolean()).isTrue();
        assertThat(suggestions.get(1).get("ok").asBoolean()).isFalse();
        assertThat(suggestions.get(1).get("message").asText()).contains("看图超时");
        assertThat(suggestions.get(2).get("ok").asBoolean()).as("慢的那张不该连坐其余张").isTrue();
        assertThat(result.get("suggested").asInt()).isEqualTo(2);
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

    /**
     * T18 重写（原为"逐张提前停止"）：并发下同波的几张已经在飞，提前停止的粒度只能是**逐波**——
     * 第一波 4 张全 40100 ⇒ 第二波 4 张不再提交，skipped 如实报 4。
     */
    @Test
    void stopsSubmittingFurtherWavesWhenLoginStateIsGoneAndReportsSkipped() throws Exception {
        for (String id : List.of("7", "8", "9", "10")) {
            ROUTES.put("/picture/get/vo?id=" + id, """
                    {"code":40100,"data":null,"message":"未登录"}""");
        }
        List<String> ids = List.of("7", "8", "9", "10", "11", "12", "13", "14");

        JsonNode result = MAPPER.readTree(new VisionTaggerTool(client(), new ConcurrentVisionModel())
                .visionTagger("100", ids));

        assertThat(REQUEST_URIS)
                .as("登录态失效后不该继续白打后面一波")
                .hasSize(VisionTaggerTool.CONCURRENCY + 1)
                .contains("/picture/tag_category")
                .doesNotContain("/picture/get/vo?id=11", "/picture/get/vo?id=12",
                        "/picture/get/vo?id=13", "/picture/get/vo?id=14");
        assertThat(result.get("skipped").asInt()).isEqualTo(4);
        assertThat(result.get("note").asText()).contains("剩余 4 张未尝试");
        assertThat(result.get("suggestions")).hasSize(4);
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
