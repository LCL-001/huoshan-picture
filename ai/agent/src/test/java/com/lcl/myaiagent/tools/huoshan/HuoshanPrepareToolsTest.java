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
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T27.6 确认门 prepare 工具单测（替代原 HuoshanWriteToolsTest）：
 * `prepareBatchEditPictures` / `prepareUrlImportPictures` 对图库 prepare 端点的
 * 请求形状（路径 / 方法 / body / 凭据）、本地形状校验（不打图库就拦）、返回载荷
 * （待确认操作 + 卡片摘要），以及**负向控制：全程零直接写请求**——
 * 直接写工具已删除，写路径只经 prepare → 用户确认 → 后端原子执行。
 * <p>
 * 桩服务器是进程内的 JDK HttpServer（与原写工具测试同一手法），按到达顺序记录请求。
 * </p>
 */
class HuoshanPrepareToolsTest {

    /** 按到达顺序记录的请求：每项 [path, method, body, satoken, cookie] */
    private static final List<String[]> REQUESTS = Collections.synchronizedList(new ArrayList<>());

    /** 桩开关：置 true 时 prepare 响应缺 summary 字段（T27.10 负向：引擎必须 fail-closed） */
    private static volatile boolean omitSummaryInStub = false;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer stubServer;

    private static String baseUrl;

    @BeforeAll
    static void startStubServer() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/", HuoshanPrepareToolsTest::handle);
        stubServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-huoshan-prepare-server");
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
        omitSummaryInStub = false;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        REQUESTS.add(new String[]{
                exchange.getRequestURI().getPath(),
                exchange.getRequestMethod(),
                body,
                exchange.getRequestHeaders().getFirst("satoken"),
                exchange.getRequestHeaders().getFirst("Cookie")
        });
        // 按请求的操作类型回同形摘要（T27.10 起 summary 由后端生成，引擎只透传）
        String operationType = "batch_edit";
        try {
            operationType = MAPPER.readTree(body).path("plan").path("operationType").asText("batch_edit");
        } catch (Exception ignored) {
            // 桩服务器：请求体不是 JSON 时按 batch_edit 兜底
        }
        String summary = "url_import".equals(operationType)
                ? "[\"目标空间：默认空间（id：30）\",\"图片直链：2 条\","
                        + "\"1. http://example.com/a.jpg\",\"2. https://example.com/b.jpg\"]"
                : "[\"目标空间：默认空间（id：30）\",\"目标图片：2 张（id：1、2）\","
                        + "\"分类 → 分类\",\"标签 → 标签A、标签B\",\"命名规则 → 落日-{序号}\"]";
        String data = omitSummaryInStub
                ? "{\"operationId\":\"op-1\",\"operationType\":\"" + operationType + "\",\"digest\":\"abc123\"}"
                : "{\"operationId\":\"op-1\",\"operationType\":\"" + operationType
                        + "\",\"digest\":\"abc123\",\"summary\":" + summary + "}";
        byte[] payload = ("{\"code\":0,\"data\":" + data + ",\"message\":\"ok\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private HuoshanApiClient client() {
        return new HuoshanApiClient(baseUrl, "satoken-stub", "session-stub",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    /** 工具调用走 ToolCallbacks（与模型真实调用路径同形） */
    private String invoke(ToolCallback[] callbacks, String name, String arguments) throws Exception {
        for (ToolCallback callback : callbacks) {
            if (callback.getToolDefinition().name().equals(name)) {
                return callback.call(arguments);
            }
        }
        throw new IllegalArgumentException("工具未注册：" + name);
    }

    private ToolCallback[] batchToolCallbacks() {
        return ToolCallbacks.from(new PrepareBatchEditPicturesTool(client(), "chat-1"));
    }

    private ToolCallback[] urlToolCallbacks() {
        return ToolCallbacks.from(new PrepareUrlImportPicturesTool(client(), "chat-1"));
    }

    // ---------- prepareBatchEditPictures：请求形状与返回载荷 ----------

    @Test
    void batchEditPreparePostsNormalizedPlanToPrepareEndpoint() throws Exception {
        String result = invoke(batchToolCallbacks(), "prepareBatchEditPictures", """
                {"spaceId":"30","pictureIdList":["1"," 2 ","1"],
                 "category":" 分类 ","tags":[" 标签A ","","标签B"],"nameRule":" 落日-{序号} "}""");

        assertThat(REQUESTS).hasSize(1);
        String[] request = REQUESTS.get(0);
        assertThat(request[0]).isEqualTo("/ai/assistant/operations/prepare");
        assertThat(request[1]).isEqualTo("POST");
        assertThat(request[3]).isEqualTo("satoken-stub");
        assertThat(request[4]).contains("SESSION=session-stub");

        JsonNode body = MAPPER.readTree(request[2]);
        assertThat(body.path("chatId").asText()).isEqualTo("chat-1");
        assertThat(body.path("plan").path("operationType").asText()).isEqualTo("batch_edit");
        JsonNode batchEdit = body.path("plan").path("batchEdit");
        assertThat(batchEdit.path("spaceId").asText()).isEqualTo("30");
        // 归一化：去空白、去重、丢空标签——卡片摘要与后端存储的计划同源
        assertThat(MAPPER.convertValue(batchEdit.path("pictureIdList"), List.class))
                .containsExactly("1", "2");
        assertThat(batchEdit.path("category").asText()).isEqualTo("分类");
        assertThat(MAPPER.convertValue(batchEdit.path("tags"), List.class))
                .containsExactly("标签A", "标签B");
        assertThat(batchEdit.path("nameRule").asText()).isEqualTo("落日-{序号}");

        JsonNode resultNode = MAPPER.readTree(
                com.lcl.myaiagent.agent.ToolCallAgent.normalizeToolResponseData(result));
        assertThat(resultNode.path("status").asText()).isEqualTo("pending_confirmation");
        assertThat(resultNode.path("operationId").asText()).isEqualTo("op-1");
        assertThat(resultNode.path("digest").asText()).isEqualTo("abc123");
        assertThat(resultNode.path("operationType").asText()).isEqualTo("batch_edit");
        // T27.10：摘要逐字透传后端返回，引擎不再自算
        assertThat(MAPPER.convertValue(resultNode.path("summary"), List.class)).containsExactly(
                "目标空间：默认空间（id：30）",
                "目标图片：2 张（id：1、2）",
                "分类 → 分类",
                "标签 → 标签A、标签B",
                "命名规则 → 落日-{序号}");
        assertThat(resultNode.path("note").asText()).contains("待确认");
    }

    /** T27.6 负向控制：prepare 全流程里只有 prepare 一个请求，直接写端点零调用 */
    @Test
    void prepareFlowNeverTouchesDirectWriteEndpoints() throws Exception {
        invoke(batchToolCallbacks(), "prepareBatchEditPictures",
                "{\"spaceId\":\"30\",\"pictureIdList\":[\"1\"],\"tags\":[\"标签A\"]}");
        invoke(urlToolCallbacks(), "prepareUrlImportPictures",
                "{\"spaceId\":\"30\",\"fileUrlList\":[\"http://example.com/a.jpg\"]}");

        assertThat(REQUESTS).hasSize(2);
        assertThat(REQUESTS).allSatisfy(request ->
                assertThat(request[0]).isEqualTo("/ai/assistant/operations/prepare"));
    }

    // ---------- 本地形状校验（不打图库就拦） ----------

    @Test
    void batchEditPrepareRejectsInvalidInputLocally() throws Exception {
        ToolCallback[] callbacks = batchToolCallbacks();

        assertThat(MAPPER.readTree(invoke(callbacks, "prepareBatchEditPictures",
                "{\"pictureIdList\":[\"1\"],\"tags\":[\"t\"]}")).path("code").asInt())
                .isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(MAPPER.readTree(invoke(callbacks, "prepareBatchEditPictures",
                "{\"spaceId\":\"30\",\"tags\":[\"t\"]}")).path("code").asInt())
                .isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(MAPPER.readTree(invoke(callbacks, "prepareBatchEditPictures",
                "{\"spaceId\":\"30\",\"pictureIdList\":[]}")).path("code").asInt())
                .isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(MAPPER.readTree(invoke(callbacks, "prepareBatchEditPictures",
                "{\"spaceId\":\"30\",\"pictureIdList\":[\"1\"]}")).path("code").asInt())
                .isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);

        assertThat(REQUESTS).as("本地校验失败不得发出任何请求").isEmpty();
    }

    @Test
    void batchEditPrepareRejectsOverCapIds() throws Exception {
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i <= PrepareBatchEditPicturesTool.MAX_IDS_PER_CALL; i++) {
            ids.append("\"").append(i + 1).append("\",");
        }
        ids.setCharAt(ids.length() - 1, ']');

        JsonNode result = MAPPER.readTree(invoke(batchToolCallbacks(), "prepareBatchEditPictures",
                "{\"spaceId\":\"30\",\"pictureIdList\":" + ids + ",\"tags\":[\"t\"]}"));

        assertThat(result.path("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(result.path("message").asText()).contains("一次最多改");
        assertThat(REQUESTS).isEmpty();
    }

    // ---------- prepareUrlImportPictures ----------

    @Test
    void urlImportPreparePostsPlanToPrepareEndpoint() throws Exception {
        String result = invoke(urlToolCallbacks(), "prepareUrlImportPictures",
                "{\"spaceId\":\"30\",\"fileUrlList\":[\"http://example.com/a.jpg\",\"https://example.com/b.jpg\"]}");

        assertThat(REQUESTS).hasSize(1);
        assertThat(REQUESTS.get(0)[0]).isEqualTo("/ai/assistant/operations/prepare");

        JsonNode body = MAPPER.readTree(REQUESTS.get(0)[2]);
        assertThat(body.path("plan").path("operationType").asText()).isEqualTo("url_import");
        assertThat(MAPPER.convertValue(body.path("plan").path("urlImport").path("fileUrls"), List.class))
                .containsExactly("http://example.com/a.jpg", "https://example.com/b.jpg");

        JsonNode resultNode = MAPPER.readTree(
                com.lcl.myaiagent.agent.ToolCallAgent.normalizeToolResponseData(result));
        assertThat(resultNode.path("status").asText()).isEqualTo("pending_confirmation");
        assertThat(resultNode.path("operationId").asText()).isEqualTo("op-1");
        assertThat(MAPPER.convertValue(resultNode.path("summary"), List.class)).containsExactly(
                "目标空间：默认空间（id：30）",
                "图片直链：2 条",
                "1. http://example.com/a.jpg",
                "2. https://example.com/b.jpg");
    }

    /** T27.10 负向：后端响应缺 summary 时引擎必须 fail-closed，不得自算摘要兜底 */
    @Test
    void prepareFailsClosedWhenBackendOmitsSummary() throws Exception {
        omitSummaryInStub = true;

        JsonNode result = MAPPER.readTree(invoke(urlToolCallbacks(), "prepareUrlImportPictures",
                "{\"spaceId\":\"30\",\"fileUrlList\":[\"http://example.com/a.jpg\"]}"));

        assertThat(result.path("code").asInt()).isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);
        assertThat(result.path("message").asText()).contains("卡片摘要");
        assertThat(REQUESTS).as("prepare 请求已发出，失败发生在解析响应").hasSize(1);
    }

    @Test
    void urlImportPrepareRejectsNonHttpAndOverCapLocally() throws Exception {
        ToolCallback[] callbacks = urlToolCallbacks();

        assertThat(MAPPER.readTree(invoke(callbacks, "prepareUrlImportPictures",
                "{\"spaceId\":\"30\",\"fileUrlList\":[\"ftp://example.com/a.jpg\"]}")).path("code").asInt())
                .isEqualTo(HuoshanApiClient.PARAMS_ERROR_CODE);

        StringBuilder urls = new StringBuilder("[");
        for (int i = 0; i <= PrepareUrlImportPicturesTool.MAX_URLS_PER_CALL; i++) {
            urls.append("\"http://example.com/").append(i).append(".jpg\",");
        }
        urls.setCharAt(urls.length() - 1, ']');
        assertThat(MAPPER.readTree(invoke(callbacks, "prepareUrlImportPictures",
                "{\"spaceId\":\"30\",\"fileUrlList\":" + urls + "}")).path("message").asText())
                .contains("一次最多导入");

        assertThat(REQUESTS).as("本地校验失败不得发出任何请求").isEmpty();
    }

    // ---------- 引擎侧 chatId 缺失（直接 new 的裸工具没有会话上下文） ----------

    @Test
    void toolsRejectPrepareWhenChatIdIsMissing() throws Exception {
        ToolCallback[] batchCallbacks = ToolCallbacks.from(new PrepareBatchEditPicturesTool(client(), " "));
        ToolCallback[] urlCallbacks = ToolCallbacks.from(new PrepareUrlImportPicturesTool(client(), null));

        assertThat(MAPPER.readTree(invoke(batchCallbacks, "prepareBatchEditPictures",
                "{\"spaceId\":\"30\",\"pictureIdList\":[\"1\"],\"tags\":[\"t\"]}")).path("message").asText())
                .contains("缺少会话标识");
        assertThat(MAPPER.readTree(invoke(urlCallbacks, "prepareUrlImportPictures",
                "{\"spaceId\":\"30\",\"fileUrlList\":[\"http://example.com/a.jpg\"]}")).path("message").asText())
                .contains("缺少会话标识");

        assertThat(REQUESTS).isEmpty();
    }
}
