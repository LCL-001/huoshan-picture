package com.lcl.myaiagent.tools.huoshan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7-lite 图库只读工具单测（docs/plan.md T7 档 1）：三个工具对图库 API 的请求形状
 * （路径 / 方法 / request body / satoken 头）与结构化分页返回定形；图库业务错误码
 * （如登录态失效 40102）转成结构化错误 JSON 而不是抛异常炸掉这一步。
 * <p>
 * 桩服务器是进程内的 JDK HttpServer，不依赖外部服务，随测试类启停。
 * </p>
 */
class HuoshanReadOnlyToolsTest {

    /** 最近一次请求的快照：路径 → [method, body, satoken, cookie] */
    private static final Map<String, String[]> LAST_REQUEST = new ConcurrentHashMap<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer stubServer;

    private static String baseUrl;

    /** 下一个响应体（各用例自行设置） */
    private static volatile String nextResponseBody = "";

    private static volatile int nextStatusCode = 200;

    @BeforeAll
    static void startStubServer() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/", HuoshanReadOnlyToolsTest::handle);
        stubServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-huoshan-server");
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

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        LAST_REQUEST.put(exchange.getRequestURI().getPath(), new String[]{
                exchange.getRequestMethod(), body,
                exchange.getRequestHeaders().getFirst(HuoshanApiClient.SATOKEN_HEADER),
                exchange.getRequestHeaders().getFirst("Cookie")});
        byte[] payload = nextResponseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(nextStatusCode, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static HuoshanApiClient client() {
        return new HuoshanApiClient(baseUrl, "test-satoken", "test-session",
                Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    @Test
    void listSpacesPostsPagedQueryWithSatokenHeader() throws Exception {
        nextStatusCode = 200;
        nextResponseBody = """
                {"code":0,"data":{"records":[
                  {"id":"2099389400592543745","spaceName":"团队空间","spaceType":1,"spaceLevel":1,"totalCount":12,"maxCount":100,
                   "totalSize":2048,"maxSize":1048576,"permissionList":["picture:view"]}],
                  "total":1,"size":10,"current":1,"pages":1},"message":"ok"}""";

        String json = new ListSpacesTool(client()).listSpaces(1, 10, null, null);

        String[] captured = LAST_REQUEST.get("/space/list/page/vo");
        assertThat(captured).as("工具必须打图库的 /space/list/page/vo").isNotNull();
        assertThat(captured[0]).isEqualTo("POST");
        assertThat(captured[2]).isEqualTo("test-satoken");
        assertThat(captured[3])
                .as("B 口径：空间接口同时要 Spring Session，cookie 必须带上")
                .isEqualTo("SESSION=test-session");
        JsonNode sent = MAPPER.readTree(captured[1]);
        assertThat(sent.get("current").asInt()).isEqualTo(1);
        assertThat(sent.get("pageSize").asInt()).isEqualTo(10);

        JsonNode result = MAPPER.readTree(json);
        assertThat(result.get("total").asLong()).isEqualTo(1);
        assertThat(result.get("hasMore").asBoolean()).isFalse();
        JsonNode item = result.get("items").get(0);
        assertThat(item.get("id").isTextual())
                .as("id 必须按字符串透传：图库 Long→字符串防 JS 精度丢失，转成数字会让模型截断 19 位雪花 id")
                .isTrue();
        assertThat(item.get("id").asText()).isEqualTo("2099389400592543745");
        assertThat(item.get("spaceName").asText()).isEqualTo("团队空间");
        assertThat(item.get("totalCount").asLong()).isEqualTo(12);
    }

    @Test
    void listPicturesCarriesSpaceIdAndComputesHasMore() throws Exception {
        nextStatusCode = 200;
        nextResponseBody = """
                {"code":0,"data":{"records":[
                  {"id":"2045431671356391425","name":"落日.jpg","url":"https://cos/1.jpg","thumbnailUrl":"https://cos/1_thumb.jpg",
                   "category":"风景","tags":["天空","落日"],"picFormat":"jpg","picSize":10240,
                   "picWidth":1920,"picHeight":1080,"spaceId":"2099389400592543745"}],
                  "total":25,"size":10,"current":2,"pages":3},"message":"ok"}""";

        String json = new ListPicturesTool(client()).listPictures("2099389400592543745", 2, 10, "落日", null);

        String[] captured = LAST_REQUEST.get("/picture/list/page/vo");
        assertThat(captured).isNotNull();
        assertThat(captured[0]).isEqualTo("POST");
        assertThat(captured[2]).isEqualTo("test-satoken");
        JsonNode sent = MAPPER.readTree(captured[1]);
        assertThat(sent.get("spaceId").isTextual())
                .as("空间 id 进出都按字符串，避免数字化后精度丢失")
                .isTrue();
        assertThat(sent.get("spaceId").asText()).isEqualTo("2099389400592543745");
        assertThat(sent.get("searchText").asText()).isEqualTo("落日");

        JsonNode result = MAPPER.readTree(json);
        assertThat(result.get("total").asLong()).isEqualTo(25);
        assertThat(result.get("hasMore").asBoolean()).as("current=2 < pages=3").isTrue();
        JsonNode item = result.get("items").get(0);
        assertThat(item.get("name").asText()).isEqualTo("落日.jpg");
        assertThat(item.get("id").asText()).isEqualTo("2045431671356391425");
        assertThat(item.get("spaceId").asText()).isEqualTo("2099389400592543745");
        assertThat(item.get("category").asText()).isEqualTo("风景");
        assertThat(item.get("tags")).hasSize(2);
    }

    @Test
    void getTagCategoryReadsDynamicVocabulary() throws Exception {
        nextStatusCode = 200;
        nextResponseBody = """
                {"code":0,"data":{"tagList":["热门","风景"],"categoryList":["模板","电商"]},"message":"ok"}""";

        String json = new GetTagCategoryTool(client()).getTagCategory();

        String[] captured = LAST_REQUEST.get("/picture/tag_category");
        assertThat(captured).isNotNull();
        assertThat(captured[0]).isEqualTo("GET");
        assertThat(captured[2]).isEqualTo("test-satoken");
        assertThat(captured[3]).isEqualTo("SESSION=test-session");

        JsonNode result = MAPPER.readTree(json);
        assertThat(result.get("tagList")).hasSize(2);
        assertThat(result.get("categoryList").get(1).asText()).isEqualTo("电商");
    }

    @Test
    void businessErrorBecomesStructuredErrorJson() throws Exception {
        nextStatusCode = 200;
        nextResponseBody = """
                {"code":40102,"data":null,"message":"空间登录态已失效，请重新登录"}""";

        String json = new ListSpacesTool(client()).listSpaces(1, 10, null, null);

        JsonNode result = MAPPER.readTree(json);
        assertThat(result.get("error").asBoolean()).isTrue();
        assertThat(result.get("code").asInt()).isEqualTo(40102);
        assertThat(result.get("message").asText()).contains("登录态已失效");
    }

    @Test
    void blankSessionIdSendsNoCookieHeader() throws Exception {
        nextStatusCode = 200;
        nextResponseBody = """
                {"code":0,"data":{"records":[],"total":0,"size":10,"current":1,"pages":0},"message":"ok"}""";

        HuoshanApiClient withoutSession = new HuoshanApiClient(baseUrl, "test-satoken", "  ",
                Duration.ofSeconds(3), Duration.ofSeconds(3));
        new ListSpacesTool(withoutSession).listSpaces(1, 10, null, null);

        assertThat(LAST_REQUEST.get("/space/list/page/vo")[3])
                .as("没有会话标识时不应发出空的 SESSION cookie")
                .isNull();
    }

    @Test
    void notLoggedInErrorIsAlsoStructured() throws Exception {        nextStatusCode = 200;
        nextResponseBody = """
                {"code":40100,"data":null,"message":"未登录"}""";

        String json = new GetTagCategoryTool(client()).getTagCategory();

        JsonNode result = MAPPER.readTree(json);
        assertThat(result.get("error").asBoolean()).isTrue();
        assertThat(result.get("code").asInt()).isEqualTo(40100);
    }
}
