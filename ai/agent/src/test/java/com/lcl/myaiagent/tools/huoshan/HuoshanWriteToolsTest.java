package com.lcl.myaiagent.tools.huoshan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 档 3 写类工具单测（docs/plan.md T7）：`batchEditPictures` 与 `batchUploadByUrl` 对图库 API 的
 * 请求形状（路径 / 方法 / body / 凭据）、参数校验（本地就拦、不打图库）、以及**逐条成败的上报形状**。
 * <p>
 * 桩服务器是进程内的 JDK HttpServer：本次要的是"一个工具内多次调用、每次不同响应"，
 * 所以响应按队列取（队列空则用默认成功响应），并把每次请求按到达顺序记下来。
 * </p>
 */
class HuoshanWriteToolsTest {

    /** 按到达顺序记录的请求：每项 [path, method, body, satoken, cookie] */
    private static final List<String[]> REQUESTS = Collections.synchronizedList(new ArrayList<>());

    /** 预设响应队列：每次请求取一个（空则用 DEFAULT_RESPONSE） */
    private static final Deque<String> RESPONSES = new ConcurrentLinkedDeque<>();

    private static final String DEFAULT_RESPONSE = """
            {"code":0,"data":true,"message":"ok"}""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer stubServer;

    private static String baseUrl;

    @BeforeAll
    static void startStubServer() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/", HuoshanWriteToolsTest::handle);
        stubServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-huoshan-write-server");
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
        REQUESTS.clear();
        RESPONSES.clear();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        REQUESTS.add(new String[]{
                exchange.getRequestURI().getPath(), exchange.getRequestMethod(), body,
                exchange.getRequestHeaders().getFirst(HuoshanApiClient.SATOKEN_HEADER),
                exchange.getRequestHeaders().getFirst("Cookie")});
        String queued = RESPONSES.poll();
        byte[] payload = (queued == null ? DEFAULT_RESPONSE : queued).getBytes(StandardCharsets.UTF_8);
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

    private static JsonNode lastRequestJson() throws Exception {
        return MAPPER.readTree(REQUESTS.get(REQUESTS.size() - 1)[2]);
    }

    // ---------- batchEditPictures ----------

    @Test
    void batchEditSendsIdsAsStringsAndPassesFieldsVerbatim() throws Exception {
        RESPONSES.add("""
                {"code":0,"data":true,"message":"ok"}""");

        String json = new BatchEditPicturesTool(client()).batchEditPictures(
                "2099389400592543745",
                List.of("2045431671356391425", " 2045431671356391426 "),
                "风景",
                List.of("天空", "落日"),
                "落日-{序号}");

        assertThat(REQUESTS).hasSize(1);
        String[] captured = REQUESTS.get(0);
        assertThat(captured[0]).as("批编必须打图库 /picture/edit/batch").isEqualTo("/picture/edit/batch");
        assertThat(captured[1]).isEqualTo("POST");
        assertThat(captured[3]).as("工具调用必须带用户真实 satoken").isEqualTo("test-satoken");
        assertThat(captured[4]).isEqualTo("SESSION=test-session");

        JsonNode sent = lastRequestJson();
        assertThat(sent.get("spaceId").isTextual())
                .as("id 进出都按字符串：19 位雪花 id 当数字处理会丢精度")
                .isTrue();
        assertThat(sent.get("spaceId").asText()).isEqualTo("2099389400592543745");
        assertThat(sent.get("pictureIdList").isArray()).isTrue();
        assertThat(sent.get("pictureIdList").get(0).isTextual()).isTrue();
        assertThat(sent.get("pictureIdList")).hasSize(2);
        assertThat(sent.get("pictureIdList").get(1).asText())
                .as("id 两侧空白应被清洗，别把带空格的 id 发给图库")
                .isEqualTo("2045431671356391426");
        assertThat(sent.get("category").asText()).isEqualTo("风景");
        assertThat(sent.get("tags").get(1).asText()).isEqualTo("落日");
        assertThat(sent.get("nameRule").asText()).as("{序号} 占位符原样透传，由图库替换").isEqualTo("落日-{序号}");

        JsonNode result = MAPPER.readTree(json);
        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(result.get("requestedCount").asInt()).isEqualTo(2);
        assertThat(result.get("appliedFields")).hasSize(3);
        assertThat(result.get("note").asText())
                .as("批编只回一个布尔：必须如实说明'只确认提交成功'，不能让模型承诺逐张改好")
                .contains("静默跳过")
                .contains("未逐张确认");
    }

    @Test
    void batchEditDeduplicatesRepeatedIds() throws Exception {
        new BatchEditPicturesTool(client()).batchEditPictures(
                "100", List.of("7", "7", "8", " "), "风景", null, null);

        JsonNode sent = lastRequestJson();
        assertThat(sent.get("pictureIdList")).hasSize(2);
        assertThat(sent.get("tags")).as("没给标签就不该出现空数组字段").isNull();
        assertThat(sent.get("category").asText()).isEqualTo("风景");
    }

    @Test
    void batchEditRejectsCallThatChangesNothingWithoutTouchingThePlatform() throws Exception {
        String json = new BatchEditPicturesTool(client()).batchEditPictures("100", List.of("7"), null, List.of(), "  ");

        assertThat(REQUESTS).as("本地就能判出的参数错不该打到图库").isEmpty();
        JsonNode result = MAPPER.readTree(json);
        assertThat(result.get("error").asBoolean()).isTrue();
        assertThat(result.get("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(result.get("message").asText()).contains("至少给一个");
    }

    @Test
    void batchEditRejectsMissingSpaceIdOrEmptyIds() throws Exception {
        BatchEditPicturesTool tool = new BatchEditPicturesTool(client());

        JsonNode noSpace = MAPPER.readTree(tool.batchEditPictures(" ", List.of("7"), "风景", null, null));
        JsonNode noIds = MAPPER.readTree(tool.batchEditPictures("100", List.of(), "风景", null, null));

        assertThat(REQUESTS).isEmpty();
        assertThat(noSpace.get("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(noSpace.get("message").asText()).contains("spaceId");
        assertThat(noIds.get("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(noIds.get("message").asText()).contains("pictureIdList");
    }

    @Test
    void batchEditRejectsOversizedBatch() throws Exception {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < BatchEditPicturesTool.MAX_IDS_PER_CALL + 1; i++) {
            tooMany.add(String.valueOf(i));
        }

        JsonNode result = MAPPER.readTree(new BatchEditPicturesTool(client())
                .batchEditPictures("100", tooMany, "风景", null, null));

        assertThat(REQUESTS).isEmpty();
        assertThat(result.get("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(result.get("message").asText()).contains("分批");
    }

    @Test
    void batchEditPermissionErrorBecomesStructuredError() throws Exception {
        RESPONSES.add("""
                {"code":40101,"data":null,"message":"没有空间访问权限"}""");

        JsonNode result = MAPPER.readTree(new BatchEditPicturesTool(client())
                .batchEditPictures("100", List.of("7"), "风景", null, null));

        assertThat(result.get("error").asBoolean()).isTrue();
        assertThat(result.get("code").asInt()).isEqualTo(40101);
        assertThat(result.get("message").asText()).isEqualTo("没有空间访问权限");
    }

    // ---------- batchUploadByUrl ----------

    @Test
    void batchUploadLoopsPerUrlAndReportsEachResult() throws Exception {
        RESPONSES.add("""
                {"code":0,"data":{"id":"2045431671356391425","name":"a.jpg","url":"https://cos/a.jpg"},"message":"ok"}""");
        RESPONSES.add("""
                {"code":50001,"data":null,"message":"图片已存在"}""");
        RESPONSES.add("""
                {"code":0,"data":{"id":"2045431671356391427","name":"c.jpg","url":"https://cos/c.jpg"},"message":"ok"}""");

        String json = new BatchUploadByUrlTool(client()).batchUploadByUrl(
                "2099389400592543745", List.of("https://img/a.jpg", "https://img/b.jpg", "https://img/c.jpg"));

        assertThat(REQUESTS).as("单张端点由工具内部循环：一个 URL 一次调用").hasSize(3);
        for (String[] captured : REQUESTS) {
            assertThat(captured[0]).isEqualTo("/picture/upload/url");
            assertThat(captured[1]).isEqualTo("POST");
            assertThat(captured[3]).isEqualTo("test-satoken");
            assertThat(captured[4]).isEqualTo("SESSION=test-session");
        }
        JsonNode sent = MAPPER.readTree(REQUESTS.get(1)[2]);
        assertThat(sent.get("spaceId").asText()).isEqualTo("2099389400592543745");
        assertThat(sent.get("fileUrl").asText()).isEqualTo("https://img/b.jpg");

        JsonNode result = MAPPER.readTree(json);
        assertThat(result.get("requested").asInt()).isEqualTo(3);
        assertThat(result.get("succeeded").asInt()).as("逐条成败：2 成功 1 失败").isEqualTo(2);
        assertThat(result.get("failed").asInt()).isEqualTo(1);
        assertThat(result.get("skipped").asInt()).isZero();
        JsonNode items = result.get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("ok").asBoolean()).isTrue();
        assertThat(items.get(0).get("pictureId").asText()).isEqualTo("2045431671356391425");
        assertThat(items.get(1).get("ok").asBoolean()).isFalse();
        assertThat(items.get(1).get("code").asInt()).isEqualTo(50001);
        assertThat(items.get(1).get("message").asText()).isEqualTo("图片已存在");
        assertThat(items.get(1).get("fileUrl").asText())
                .as("失败条目要能对上号：用户才知道是哪一张没进")
                .isEqualTo("https://img/b.jpg");
    }

    @Test
    void batchUploadStopsEarlyOnExpiredLoginInsteadOfBurningTheRest() throws Exception {
        RESPONSES.add("""
                {"code":40100,"data":null,"message":"未登录"}""");

        JsonNode result = MAPPER.readTree(new BatchUploadByUrlTool(client()).batchUploadByUrl(
                "100", List.of("https://img/a.jpg", "https://img/b.jpg", "https://img/c.jpg")));

        assertThat(REQUESTS).as("登录态失效后剩余条目必然同样失败，不该继续白烧").hasSize(1);
        assertThat(result.get("requested").asInt()).isEqualTo(3);
        assertThat(result.get("succeeded").asInt()).isZero();
        assertThat(result.get("failed").asInt()).isEqualTo(1);
        assertThat(result.get("skipped").asInt()).isEqualTo(2);
        assertThat(result.get("note").asText()).contains("剩余 2 条未尝试");
    }

    @Test
    void batchUploadRejectsBlankSpaceIdOrEmptyUrls() throws Exception {
        BatchUploadByUrlTool tool = new BatchUploadByUrlTool(client());

        JsonNode noSpace = MAPPER.readTree(tool.batchUploadByUrl(null, List.of("https://img/a.jpg")));
        JsonNode noUrls = MAPPER.readTree(tool.batchUploadByUrl("100", List.of("  ")));

        assertThat(REQUESTS).isEmpty();
        assertThat(noSpace.get("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(noSpace.get("message").asText()).contains("spaceId");
        assertThat(noUrls.get("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(noUrls.get("message").asText()).contains("fileUrls");
    }

    @Test
    void batchUploadRejectsOversizedBatch() throws Exception {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < BatchUploadByUrlTool.MAX_URLS_PER_CALL + 1; i++) {
            tooMany.add("https://img/" + i + ".jpg");
        }

        JsonNode result = MAPPER.readTree(new BatchUploadByUrlTool(client()).batchUploadByUrl("100", tooMany));

        assertThat(REQUESTS).isEmpty();
        assertThat(result.get("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(result.get("message").asText()).contains("分批");
    }

    @Test
    void strictSchemaPublishesListParametersAsArrays() throws Exception {
        JsonNode editSchema = inputSchemaOfTool("batchEditPictures");
        assertThat(editSchema.at("/properties/pictureIdList/type").asText())
                .as("模型靠 schema 才知道要传数组而不是字符串：schema=%s", editSchema)
                .isEqualTo("array");
        assertThat(editSchema.at("/properties/tags/type").asText())
                .as("schema=%s", editSchema)
                .isEqualTo("array");

        JsonNode uploadSchema = inputSchemaOfTool("batchUploadByUrl");
        assertThat(uploadSchema.at("/properties/fileUrls/type").asText())
                .as("模型靠 schema 才知道要传数组而不是字符串：schema=%s", uploadSchema)
                .isEqualTo("array");
        assertThat(uploadSchema.at("/properties/spaceId/type").asText())
                .as("schema=%s", uploadSchema)
                .isEqualTo("string");
    }

    private static JsonNode inputSchemaOfTool(String toolName) throws Exception {
        HuoshanApiClient client = client();
        ToolCallback[] callbacks = ToolCallbacks.from(
                new BatchEditPicturesTool(client), new BatchUploadByUrlTool(client));
        return MAPPER.readTree(Arrays.stream(callbacks)
                .filter(callback -> toolName.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("工具不存在：" + toolName))
                .getToolDefinition()
                .inputSchema());
    }
}
