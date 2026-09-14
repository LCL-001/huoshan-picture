package com.lcl.myaiagent.tools.huoshan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lcl.myaiagent.tools.huoshan.dto.PageResult;
import com.lcl.myaiagent.tools.huoshan.dto.PictureItem;
import com.lcl.myaiagent.tools.huoshan.dto.SpaceItem;
import com.lcl.myaiagent.tools.huoshan.dto.TagCategoryResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 图库后端 API 客户端（T7 档 1，B 口径补齐凭据组）：per-request 实例，构造期注入图库地址与**调用者的一组用户名凭据**。
 * <p>
 * 为什么要两把凭据（用户 2026-09-14 拍板方案 B）：
 * 图库的接口在登录态上分两类——空间列表/详情与批量编辑读的是 Spring Session（{@code USER_LOGIN_STATE}），
 * 而空间维度的图片读有加强校验 {@code checkSpaceViewPermission}，要求 Spring Session 用户与 sa-token 的 loginId
 * **完全一致**。只带 satoken 时空间列表直接 40100、空间维度读图 40102；两把都带才能覆盖全部只读工具。
 * </p>
 * <p>
 * 凭据只活在这个对象里（请求生命周期），不落库、不缓存、不进日志；所有图库操作都以用户自己的身份发出，
 * RBAC 由图库服务端判定，引擎没有越权能力（设计文档 L95）。
 * </p>
 */
public class HuoshanApiClient {

    /** 与图库 backend 的 sa-token token-name 保持一致，同一个值可原样转发 */
    public static final String SATOKEN_HEADER = "satoken";

    /** Spring Session 的会话 Cookie 名（图库 backend 未自定义 server.servlet.session.cookie.name，用默认值） */
    public static final String SESSION_COOKIE_NAME = "SESSION";

    /** 图库业务 code：0 = 成功 */
    private static final int SUCCESS_CODE = 0;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;

    private final String satoken;

    /** 用户会话标识：可为空（词表等公开接口不需要），空间类接口必须带 */
    private final String sessionId;

    public HuoshanApiClient(String baseUrl, String satoken, String sessionId,
                            Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.satoken = satoken;
        this.sessionId = sessionId;
    }

    /**
     * 可见空间分页（图库 POST /space/list/page/vo）。
     */
    public PageResult<SpaceItem> listSpaces(Integer pageNum, Integer pageSize, String spaceName, Integer spaceType) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("current", normalizePage(pageNum));
        body.put("pageSize", normalizePageSize(pageSize));
        if (spaceName != null && !spaceName.isBlank()) {
            body.put("spaceName", spaceName);
        }
        if (spaceType != null) {
            body.put("spaceType", spaceType);
        }
        return toPageResult(postForData("/space/list/page/vo", body), this::toSpaceItem);
    }

    /**
     * 图片分页（图库 POST /picture/list/page/vo）。
     */
    public PageResult<PictureItem> listPictures(Long spaceId, Integer pageNum, Integer pageSize,
                                                String searchText, String category) {
        ObjectNode body = MAPPER.createObjectNode();
        if (spaceId != null) {
            body.put("spaceId", spaceId);
        }
        body.put("current", normalizePage(pageNum));
        body.put("pageSize", normalizePageSize(pageSize));
        if (searchText != null && !searchText.isBlank()) {
            body.put("searchText", searchText);
        }
        if (category != null && !category.isBlank()) {
            body.put("category", category);
        }
        return toPageResult(postForData("/picture/list/page/vo", body), this::toPictureItem);
    }

    /**
     * 动态词表（图库 GET /picture/tag_category）。
     */
    public TagCategoryResult getTagCategory() {
        JsonNode data = getForData("/picture/tag_category");
        TagCategoryResult result = new TagCategoryResult();
        result.setTagList(textList(data.get("tagList")));
        result.setCategoryList(textList(data.get("categoryList")));
        return result;
    }

    private JsonNode postForData(String path, ObjectNode body) {
        String raw = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SATOKEN_HEADER, satoken)
                .headers(this::addSessionCookie)
                .body(body.toString())
                .retrieve()
                .body(String.class);
        return unwrap(raw, path);
    }

    private JsonNode getForData(String path) {
        String raw = restClient.get()
                .uri(path)
                .header(SATOKEN_HEADER, satoken)
                .headers(this::addSessionCookie)
                .retrieve()
                .body(String.class);
        return unwrap(raw, path);
    }

    /**
     * 无会话标识时不发 Cookie 头（词表等公开接口用不到），避免发出无意义的空 cookie。
     */
    private void addSessionCookie(HttpHeaders headers) {
        if (sessionId != null && !sessionId.isBlank()) {
            headers.add(HttpHeaders.COOKIE, SESSION_COOKIE_NAME + "=" + sessionId);
        }
    }

    /** 解包图库统一返回信封 {code,data,message} */
    private JsonNode unwrap(String raw, String path) {
        if (raw == null || raw.isBlank()) {
            throw new HuoshanApiException(-1, "图库接口无响应内容：" + path);
        }
        try {
            JsonNode envelope = MAPPER.readTree(raw);
            int code = envelope.path("code").asInt(-1);
            if (code != SUCCESS_CODE) {
                throw new HuoshanApiException(code, envelope.path("message").asText("图库接口返回错误：" + path));
            }
            return envelope.path("data");
        } catch (HuoshanApiException e) {
            throw e;
        } catch (Exception e) {
            throw new HuoshanApiException(-1, "图库接口返回无法解析：" + path);
        }
    }

    private <T> PageResult<T> toPageResult(JsonNode data, Function<JsonNode, T> itemMapper) {
        PageResult<T> page = new PageResult<>();
        page.setTotal(data.path("total").asLong(0));
        page.setCurrent(data.path("current").asLong(0));
        page.setPageSize(data.path("size").asLong(0));
        page.setHasMore(page.getCurrent() < data.path("pages").asLong(0));
        List<T> items = new ArrayList<>();
        for (JsonNode record : data.path("records")) {
            items.add(itemMapper.apply(record));
        }
        page.setItems(items);
        return page;
    }

    private SpaceItem toSpaceItem(JsonNode node) {
        SpaceItem item = new SpaceItem();
        item.setId(longOrNull(node, "id"));
        item.setSpaceName(textOrNull(node, "spaceName"));
        item.setSpaceType(intOrNull(node, "spaceType"));
        item.setSpaceLevel(intOrNull(node, "spaceLevel"));
        item.setTotalCount(longOrNull(node, "totalCount"));
        item.setMaxCount(longOrNull(node, "maxCount"));
        item.setTotalSize(longOrNull(node, "totalSize"));
        item.setMaxSize(longOrNull(node, "maxSize"));
        item.setPermissionList(textList(node.get("permissionList")));
        return item;
    }

    private PictureItem toPictureItem(JsonNode node) {
        PictureItem item = new PictureItem();
        item.setId(longOrNull(node, "id"));
        item.setName(textOrNull(node, "name"));
        item.setUrl(textOrNull(node, "url"));
        item.setThumbnailUrl(textOrNull(node, "thumbnailUrl"));
        item.setCategory(textOrNull(node, "category"));
        item.setTags(textList(node.get("tags")));
        item.setPicFormat(textOrNull(node, "picFormat"));
        item.setPicSize(longOrNull(node, "picSize"));
        item.setPicWidth(intOrNull(node, "picWidth"));
        item.setPicHeight(intOrNull(node, "picHeight"));
        item.setSpaceId(longOrNull(node, "spaceId"));
        return item;
    }

    private static int normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    /** 单页上限 50：够模型"看一批"，又不至于把上下文一口气塞满 */
    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 50);
    }

    private static List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> values.add(element.asText()));
        }
        return values;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }
}
