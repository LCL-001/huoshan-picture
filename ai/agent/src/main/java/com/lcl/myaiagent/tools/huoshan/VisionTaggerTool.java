package com.lcl.myaiagent.tools.huoshan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.myaiagent.common.ErrorCode;
import com.lcl.myaiagent.exception.BusinessException;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
 * 并发与时限（T18，2026-09-15 用户拍板按 T15 的"层次一"对齐，与 backend {@code PictureAiTagManager} 同口径）：
 * 一次最多 {@value #MAX_IMAGES_PER_CALL} 张，用**有界池并发 {@value #CONCURRENCY}** 看图、
 * **单张 {@value #PER_IMAGE_SECONDS} 秒墙钟预算**、单张超时或失败**只降级该张**、结果**按输入顺序**落位。
 * 老口径是逐张串行 + 单张吃满 HTTP 超时（120s）：8 张最坏十几分钟，而这条链路上限是 SSE emitter 的 300s——
 * 那样连"失败"都来不及报给用户。
 * </p>
 */
@Slf4j
public class VisionTaggerTool {

    /** 单次上限：与 backend 的 {@code app.ai.vision.max-per-request} 同口径 */
    static final int MAX_IMAGES_PER_CALL = 8;

    /** 有界并发：同时看几张（层次一；与 backend 的 {@code app.ai.vision.concurrency} 同口径） */
    static final int CONCURRENCY = 4;

    /** 单张看图的墙钟预算（与 backend 的 {@code app.ai.vision.timeout-ms} 同口径） */
    static final Duration PER_IMAGE_TIMEOUT = Duration.ofSeconds(45);

    /** 单张墙钟预算的秒数（供工具描述与日志用；与 {@link #PER_IMAGE_TIMEOUT} 同源） */
    static final int PER_IMAGE_SECONDS = 45;

    /** 登录态失效类错误码：命中即停止提交后续批次（后面每张都会同样失败） */
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

    /** 并发度与单张预算：构造期定死，便于测试注入更小的值 */
    private final int concurrency;

    private final Duration perImageTimeout;

    public VisionTaggerTool(HuoshanApiClient client, ChatModel visionModel) {
        this(client, visionModel, CONCURRENCY, PER_IMAGE_TIMEOUT);
    }

    /** 包级可见（测试用）：注入更小的并发度/单张预算——既能跑得快，也能钉住"单张超时只降级该张" */
    VisionTaggerTool(HuoshanApiClient client, ChatModel visionModel, int concurrency, Duration perImageTimeout) {
        this.client = client;
        this.visionClient = ChatClient.builder(visionModel).build();
        this.concurrency = Math.max(1, concurrency);
        this.perImageTimeout = perImageTimeout;
    }

    @Tool(description = """
            看图片内容，逐张给出标签与分类建议（只读，不改任何数据）。
            用法：先用 listPictures 拿到图片 id（字符串），再把要打标的 id 列表交给本工具；
            工具会自己去取图片地址并看图（多张并发，一次最多 8 张，要整理更多就先分批），
            返回 JSON：requested/suggested/skipped/suggestions[]/note，
            suggestions[] 与传入的 id 列表**顺序一一对应**，每条含 pictureId、ok、tags[]、category，失败时还有 message。
            建议**不会自动落库**：拿到建议后，先把要采用的部分讲给用户，用户确认后再调 batchEditPictures 落库。
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
        ExecutorService pool = newPool(Math.min(concurrency, ids.size()));
        try {
            // 按"波"提交：波内并发看图，波与波之间检查上一波有没有撞上登录态失效——命中就不再提交后续批次。
            // 并发下"逐张检查"做不到（同波的几张已经在飞了），所以提前停止的粒度是**逐波**而不是逐张。
            for (int from = 0; from < ids.size(); from += concurrency) {
                int to = Math.min(from + concurrency, ids.size());
                boolean fatalAuth = lookAtWave(pool, ids.subList(from, to), prompt, result);
                if (fatalAuth) {
                    result.setSkipped(ids.size() - to);
                    result.setNote("图库返回登录态失效（40100/40102），已停止提交后续批次；剩余 "
                            + result.getSkipped() + " 张未尝试。");
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        result.setSuggested((int) result.getSuggestions().stream().filter(VisionTagSuggestion::isOk).count());
        if (result.getNote() == null) {
            result.setNote("以上仅为建议，尚未写入图库；请把要采用的部分告诉用户，确认后再调 batchEditPictures。");
        }
        return result;
    }

    /**
     * 看一波（并发度以内的若干张），按输入顺序把结果落进 {@code result}。
     *
     * @return 这一波里有没有遇到登录态失效（决定要不要放弃后续批次）
     */
    private boolean lookAtWave(ExecutorService pool, List<String> wave, String prompt, VisionTagResult result) {
        List<Future<PictureOutcome>> futures = new ArrayList<>(wave.size());
        for (String pictureId : wave) {
            futures.add(pool.submit(() -> lookAtOne(pictureId, prompt)));
        }
        boolean fatalAuth = false;
        for (int i = 0; i < wave.size(); i++) {
            PictureOutcome outcome = awaitOne(futures.get(i), wave.get(i));
            result.getSuggestions().add(outcome.suggestion());
            fatalAuth = fatalAuth || outcome.fatalAuth();
        }
        return fatalAuth;
    }

    /** 单张：取地址 → 调多模态模型 → 解析建议；图库业务错误只降级这一张（登录态失效标记成致命） */
    private PictureOutcome lookAtOne(String pictureId, String prompt) {
        try {
            PictureItem picture = client.getPicture(pictureId);
            return new PictureOutcome(suggest(pictureId, picture, prompt), false);
        } catch (HuoshanApiException e) {
            return new PictureOutcome(failed(pictureId, null, e.getCode() + "：" + e.getMessage()),
                    FATAL_AUTH_CODES.contains(e.getCode()));
        }
    }

    /** 收单张结果：超时/异常都降级成"这一张失败"，不向外抛（同波其余张照常返回） */
    private PictureOutcome awaitOne(Future<PictureOutcome> future, String pictureId) {
        try {
            return future.get(perImageTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("看图超时, pictureId={}", pictureId);
            return new PictureOutcome(failed(pictureId, null,
                    "看图超时（超过 " + perImageTimeout.toSeconds() + "s），已跳过这张"), false);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("看图失败, pictureId={}, error={}", pictureId, cause.toString());
            return new PictureOutcome(failed(pictureId, null, "看图失败：" + cause.getMessage()), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "看图被中断");
        }
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

    /** 每请求一个独立有界池：守护线程、有界队列、拒绝策略兜底（队列按单次上限留足，正常不会触发） */
    private ExecutorService newPool(int poolSize) {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                poolSize, poolSize, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_IMAGES_PER_CALL),
                runnable -> {
                    Thread thread = new Thread(runnable, "vision-tagger-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** 单张的结果：建议本身 + 是否因登录态失效而致命（后者决定要不要放弃后续批次） */
    private record PictureOutcome(VisionTagSuggestion suggestion, boolean fatalAuth) {
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
