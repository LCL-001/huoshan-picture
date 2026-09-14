package com.lcl.myaiagent.tools.huoshan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.myaiagent.tools.huoshan.dto.PictureItem;
import com.lcl.myaiagent.tools.huoshan.dto.TagCategoryResult;
import com.lcl.myaiagent.tools.huoshan.dto.VisionTagResult;
import com.lcl.myaiagent.tools.huoshan.dto.VisionTagSuggestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Set;

/**
 * 看图打标（T7 档 3，**只读**）：整理管家的"眼睛"（设计文档 L58/L62）。
 * <p>
 * 三个设计取舍：
 * 1. **看图收在工具内**：模型只给 id 列表，工具自己去图库取图片地址、自己逐张调多模态模型——
 *    单步完成 N 张（步数友好），也免得让模型复制粘贴长 URL（那是它最容易出错的环节）；
 * 2. **只给建议、不落库**：落库由 {@code batchEditPictures} 承担，且要等用户确认（设计文档 L58）；
 * 3. **提示词注入当前词表**：优先复用已有标签/分类，词表缺词才提议新标签——词表有分寸地生长（设计文档 L63）。
 * </p>
 * <p>
 * 一次最多 {@value #MAX_IMAGES_PER_CALL} 张：逐张串行调模型（每张数秒），太多会把一步拖成超长请求。
 * </p>
 */
@Slf4j
public class VisionTaggerTool {

    /** 单次上限：逐张串行调多模态模型，8 张已是数十秒量级 */
    static final int MAX_IMAGES_PER_CALL = 8;

    /** 登录态失效类错误码：命中即停止后续张（后面每张都会同样失败） */
    private static final Set<Integer> FATAL_AUTH_CODES = Set.of(40100, 40102);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROMPT_TEMPLATE = """
            你是图库的整理助手，正在帮用户归档图片。请只看这张图片的画面内容，给出打标建议。
            当前词表（**优先复用**，不要同义改写——同义词请直接用词表里的那个词）：
            - 已有标签：%s
            - 已有分类：%s
            规则：
            1. 从已有标签里挑 1-5 个最贴切的；
            2. 词表确实没有合适的词时，才给新标签（简短名词，2-6 个字，不要生造长句）；
            3. 分类只能从已有分类里挑一个；都不合适就给空字符串，不要自造分类；
            4. 只输出一行 JSON，不要解释、不要 Markdown 代码块，格式：
            {"tags":["标签1","标签2"],"category":"分类"}
            """;

    private static final String EMPTY_VOCABULARY = "（暂无）";

    private final HuoshanApiClient client;

    private final ChatClient visionClient;

    public VisionTaggerTool(HuoshanApiClient client, ChatModel visionModel) {
        this.client = client;
        this.visionClient = ChatClient.builder(visionModel).build();
    }

    @Tool(description = """
            看图片内容，逐张给出标签与分类建议（只读，不改任何数据）。
            用法：先用 listPictures 拿到图片 id（字符串），再把要打标的 id 列表交给本工具；
            工具会自己去取图片地址并逐张看图，返回 JSON：requested/suggested/skipped/suggestions[]/note，
            suggestions[] 每条含 pictureId、ok、tags[]、category，失败时还有 message。
            建议**不会自动落库**：拿到建议后，先把要采用的部分讲给用户，用户确认后再调 batchEditPictures 落库。
            一次最多 8 张，要整理更多就先分批。
            """)
    public String visionTagger(
            @ToolParam(description = "空间 id（字符串，原样复制 listSpaces 返回的 items[].id）") String spaceId,
            @ToolParam(description = "要打标的图片 id 列表（字符串，原样复制 listPictures 返回的 items[].id），一次最多 8 张") List<String> pictureIdList) {
        return HuoshanToolSupport.render(() -> tag(spaceId, pictureIdList));
    }

    private VisionTagResult tag(String spaceId, List<String> pictureIdList) {
        if (spaceId == null || spaceId.isBlank()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "缺少 spaceId：先调 listSpaces 取空间 id");
        }
        List<String> ids = HuoshanToolSupport.cleanList(pictureIdList);
        if (ids.isEmpty()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "缺少 pictureIdList：先调 listPictures 取图片 id");
        }
        if (ids.size() > MAX_IMAGES_PER_CALL) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "一次最多看 " + MAX_IMAGES_PER_CALL + " 张，本次给了 " + ids.size() + " 张，请分批调用");
        }

        TagCategoryResult vocabulary = client.getTagCategory();
        String prompt = PROMPT_TEMPLATE.formatted(
                joinOrPlaceholder(vocabulary.getTagList()), joinOrPlaceholder(vocabulary.getCategoryList()));

        VisionTagResult result = new VisionTagResult();
        result.setSpaceId(spaceId);
        result.setRequested(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            String pictureId = ids.get(i);
            try {
                PictureItem picture = client.getPicture(pictureId);
                result.getSuggestions().add(suggest(pictureId, picture, prompt));
            } catch (HuoshanApiException e) {
                result.getSuggestions().add(failed(pictureId, null, e.getCode() + "：" + e.getMessage()));
                if (FATAL_AUTH_CODES.contains(e.getCode())) {
                    result.setSkipped(ids.size() - i - 1);
                    result.setNote("图库返回 " + e.getCode() + "（" + e.getMessage() + "），"
                            + "后续张会同样失败，已提前停止；剩余 " + result.getSkipped() + " 张未尝试。");
                    break;
                }
            }
        }
        result.setSuggested((int) result.getSuggestions().stream().filter(VisionTagSuggestion::isOk).count());
        if (result.getNote() == null) {
            result.setNote("以上仅为建议，尚未写入图库；请把要采用的部分告诉用户，确认后再调 batchEditPictures。");
        }
        return result;
    }

    /** 取图片地址 → 调多模态模型 → 解析建议；单张失败只降级这一张 */
    private VisionTagSuggestion suggest(String pictureId, PictureItem picture, String prompt) {
        String url = picture == null ? null : picture.getUrl();
        if (url == null || url.isBlank()) {
            return failed(pictureId, url, "这张图没有可访问的图片地址，无法看图");
        }
        try {
            URL imageUrl = URI.create(url).toURL();
            String content = visionClient.prompt()
                    .user(spec -> spec.text(prompt).media(mimeTypeOf(url), imageUrl))
                    .call()
                    .content();
            return parse(pictureId, url, content);
        } catch (Exception e) {
            log.warn("看图失败, pictureId={}, error={}", pictureId, e.toString());
            return failed(pictureId, url, "看图失败：" + e.getMessage());
        }
    }

    /** 解析模型返回：容错剥掉 Markdown 代码块，只认第一段 JSON 对象 */
    private static VisionTagSuggestion parse(String pictureId, String url, String raw) {
        if (raw == null || raw.isBlank()) {
            return failed(pictureId, url, "模型没有返回内容");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return failed(pictureId, url, "模型返回无法解析：" + abbreviate(raw));
        }
        try {
            JsonNode node = MAPPER.readTree(raw.substring(start, end + 1));
            VisionTagSuggestion suggestion = new VisionTagSuggestion();
            suggestion.setPictureId(pictureId);
            suggestion.setUrl(url);
            suggestion.setOk(true);
            JsonNode tags = node.get("tags");
            if (tags != null && tags.isArray()) {
                tags.forEach(tag -> {
                    String text = tag.asText();
                    if (text != null && !text.isBlank()) {
                        suggestion.getTags().add(text.trim());
                    }
                });
            }
            JsonNode category = node.get("category");
            suggestion.setCategory(category == null || category.isNull() ? "" : category.asText("").trim());
            return suggestion;
        } catch (Exception e) {
            return failed(pictureId, url, "模型返回无法解析：" + abbreviate(raw));
        }
    }

    private static VisionTagSuggestion failed(String pictureId, String url, String message) {
        VisionTagSuggestion suggestion = new VisionTagSuggestion();
        suggestion.setPictureId(pictureId);
        suggestion.setUrl(url);
        suggestion.setOk(false);
        suggestion.setMessage(message);
        return suggestion;
    }

    /** Spring 的 MimeTypeUtils 没有 webp 常量（图库支持 jpg/png/webp 三种落库格式），自己补一个 */
    private static final MimeType IMAGE_WEBP = MimeType.valueOf("image/webp");

    /** 图片地址后缀决定 MIME；认不出就按 JPEG 发（图库落库的格式只可能是 jpg/jpeg/png/webp） */
    private static MimeType mimeTypeOf(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith(".png")) {
            return MimeTypeUtils.IMAGE_PNG;
        }
        if (lower.endsWith(".webp")) {
            return IMAGE_WEBP;
        }
        return MimeTypeUtils.IMAGE_JPEG;
    }

    private static String joinOrPlaceholder(List<String> values) {
        return values == null || values.isEmpty() ? EMPTY_VOCABULARY : String.join("、", values);
    }

    private static String abbreviate(String raw) {
        String flat = raw.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }
}
