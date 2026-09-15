package com.lcl.yunpicturebackend.manager.ai;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.api.vision.AiVisionTagApi;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.vo.PictureAiTagSuggestionVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.utils.TextSanitizeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 打标管理器：提示词、并发看图、容错解析、以及"只改标签与分类"的更新条件。
 * <p>
 * <b>并发（2026-09-15 用户拍板的"层次一"）</b>：一次请求内的多张图用**有界线程池**并行看图——
 * 逐张串行在 MiMo 上单张 10~15s，8 张要一两分钟，并行 4 后约 30~40s。三条硬约束：
 * 1. 有界池、每请求独立创建并 shutdown，**不用公共 ForkJoinPool**（阻塞 IO 占满公共池会殃及全 JVM）；
 * 2. **单张独立计时**（{@code future.get(timeout)}）：一张挂死不拖垮整批，超时那张降级、其余照常；
 * 3. **结果按输入顺序落位**：按下标收集，不随完成先后乱序（管理页要按图核对）。
 * </p>
 * <p>
 * 提示词与引擎侧 {@code VisionTaggerTool.PROMPT_TEMPLATE} 保持同一口径（优先复用已有词表、只输出一行 JSON），
 * 这里自带一份是为了让"公共图库打标"不依赖引擎进程与用户凭据（见 docs/plan.md T15）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PictureAiTagManager {

    /** 单条标签长度上限：词表里的词是 2~6 字，模型偶尔会吐一句话，超限即拒（列宽 varchar(512) 另在服务层拦总长） */
    public static final int MAX_TAG_LENGTH = 32;

    /** 分类长度上限，与 picture.category 列宽一致 */
    public static final int MAX_CATEGORY_LENGTH = 64;

    private static final String EMPTY_VOCABULARY = "（暂无）";

    /** 与引擎 VisionTaggerTool 同口径：只认画面内容、优先复用词表、只输出一行 JSON */
    static final String PROMPT_TEMPLATE = """
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiVisionTagApi visionTagApi;

    /** 并发度：同时看几张图（层次一） */
    @Value("${app.ai.vision.concurrency:4}")
    private int concurrency;

    /** 单次请求最多几张图 */
    @Value("${app.ai.vision.max-per-request:8}")
    private int maxPerRequest;

    /** 单张看图的耗时上界（毫秒），与 HTTP 超时同口径，作为"这张不拖垮整批"的兜底 */
    @Value("${app.ai.vision.timeout-ms:45000}")
    private int timeoutMs;

    public int getMaxPerRequest() {
        return maxPerRequest;
    }

    /** 打标模型是否已配置：未配置时调用方给一句能照做的文案，而不是发满 N 次注定失败的请求 */
    public boolean isConfigured() {
        return visionTagApi.isConfigured();
    }

    /**
     * 逐张看图出建议（**只读，不写库**）。
     *
     * @param pictures    待打标的图片（顺序即返回顺序）
     * @param tagNames    当前标签词表
     * @param categoryNames 当前分类词表
     * @return 每张一条建议；单张失败/超时只降级该条，不抛异常
     */
    public List<PictureAiTagSuggestionVO> suggest(List<Picture> pictures, List<String> tagNames,
                                                  List<String> categoryNames) {
        if (CollUtil.isEmpty(pictures)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请先选择要打标的图片");
        }
        if (pictures.size() > maxPerRequest) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "单次最多 " + maxPerRequest + " 张，本次选了 " + pictures.size() + " 张，请分批");
        }
        String prompt = PROMPT_TEMPLATE.formatted(joinOrPlaceholder(tagNames), joinOrPlaceholder(categoryNames));
        ExecutorService pool = newPool(Math.min(concurrency, pictures.size()));
        try {
            List<Future<PictureAiTagSuggestionVO>> futures = new ArrayList<>(pictures.size());
            for (Picture picture : pictures) {
                futures.add(pool.submit(() -> suggestOne(picture, prompt)));
            }
            List<PictureAiTagSuggestionVO> suggestions = new ArrayList<>(pictures.size());
            for (int i = 0; i < pictures.size(); i++) {
                suggestions.add(awaitOne(futures.get(i), pictures.get(i)));
            }
            return suggestions;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 取单张结果：超时/异常都降级成这条建议失败，不向外抛（整批照常返回） */
    private PictureAiTagSuggestionVO awaitOne(Future<PictureAiTagSuggestionVO> future, Picture picture) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("看图超时, pictureId={}", picture.getId());
            return failed(picture, "看图超时（超过 " + timeoutMs / 1000 + "s），已跳过这张");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("看图失败, pictureId={}, error={}", picture.getId(), cause.toString());
            return failed(picture, reasonOf(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "打标被中断");
        }
    }

    /** 单张：取地址 → 调多模态 → 解析建议；一切异常在此收敛成"这张失败" */
    private PictureAiTagSuggestionVO suggestOne(Picture picture, String prompt) {
        String url = picture.getUrl();
        if (StrUtil.isBlank(url)) {
            return failed(picture, "这张图没有可访问的图片地址，无法看图");
        }
        try {
            return parse(picture, url, visionTagApi.describeImage(url, prompt));
        } catch (Exception e) {
            log.warn("看图失败, pictureId={}, error={}", picture.getId(), e.toString());
            return failed(picture, reasonOf(e));
        }
    }

    /**
     * 把建议写进**更新条件**（而不是整实体 update）：SET 子句里只有 tags 与 category，
     * 审核四字段（reviewStatus / reviewerId / reviewMessage / reviewTime）**结构性不可能被改到**。
     * <p>
     * 这是本功能最要紧的不变量：公共图库的图若被"打回待审核"就会从公共图库消失；
     * 若走单张 editPicture，fillReviewParams 还会把 reviewerId/reviewMessage 覆盖成"管理员自动过审"。
     * 空值不写（与 editPictureByBatch 同口径：空 = 不改，避免用空建议清掉已有标签）。
     * </p>
     */
    public static void applyTo(LambdaUpdateWrapper<Picture> update, List<String> tags, String category) {
        if (CollUtil.isNotEmpty(tags)) {
            update.set(Picture::getTags, JSONUtil.toJsonStr(tags));
        }
        if (StrUtil.isNotBlank(category)) {
            update.set(Picture::getCategory, category);
        }
    }

    /** 标签与分类的清洗与长度校验：模型输出属不可信输入（转义 HTML + 限长），非法即拒 */
    public static List<String> sanitizeTags(List<String> tags) {
        if (CollUtil.isEmpty(tags)) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(tags.size());
        for (String tag : tags) {
            String text = TextSanitizeUtils.stripHtml(tag);
            if (StrUtil.isBlank(text)) {
                continue;
            }
            text = text.trim();
            if (text.length() > MAX_TAG_LENGTH) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "标签过长：" + text);
            }
            if (!cleaned.contains(text)) {
                cleaned.add(text);
            }
        }
        return cleaned;
    }

    /** 分类清洗与限长（空串合法：表示"模型没给出合适分类"，此时不写库） */
    public static String sanitizeCategory(String category) {
        String text = TextSanitizeUtils.stripHtml(category);
        if (StrUtil.isBlank(text)) {
            return "";
        }
        text = text.trim();
        if (text.length() > MAX_CATEGORY_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分类过长：" + text);
        }
        return text;
    }

    /** 失败建议：带图 id 与原因，供管理页逐张显示 */
    private static PictureAiTagSuggestionVO failed(Picture picture, String message) {
        PictureAiTagSuggestionVO suggestion = new PictureAiTagSuggestionVO();
        suggestion.setPictureId(picture.getId());
        suggestion.setUrl(picture.getUrl());
        suggestion.setOk(false);
        suggestion.setMessage(message);
        return suggestion;
    }

    /** 面向管理员的失败原因：业务异常用自己的文案（不含厂商原文），其余给一句通用文案，原文只进日志 */
    private static String reasonOf(Throwable cause) {
        if (cause instanceof BusinessException) {
            String message = ((BusinessException) cause).getMessage();
            if (StrUtil.isNotBlank(message)) {
                return message;
            }
        }
        return "看图失败，请稍后重试";
    }

    /** 解析模型返回：容错剥掉 Markdown 代码块/前后杂文本，只认第一段 JSON 对象 */
    static PictureAiTagSuggestionVO parse(Picture picture, String url, String raw) {
        PictureAiTagSuggestionVO suggestion = new PictureAiTagSuggestionVO();
        suggestion.setPictureId(picture.getId());
        suggestion.setUrl(url);
        if (StrUtil.isBlank(raw)) {
            suggestion.setOk(false);
            suggestion.setMessage("模型没有返回内容");
            return suggestion;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            suggestion.setOk(false);
            suggestion.setMessage("模型返回无法解析：" + abbreviate(raw));
            return suggestion;
        }
        try {
            JsonNode node = MAPPER.readTree(raw.substring(start, end + 1));
            List<String> tags = new ArrayList<>();
            JsonNode tagsNode = node.get("tags");
            if (tagsNode != null && tagsNode.isArray()) {
                tagsNode.forEach(tag -> {
                    String text = tag.asText();
                    if (StrUtil.isNotBlank(text)) {
                        tags.add(text.trim());
                    }
                });
            }
            JsonNode categoryNode = node.get("category");
            String category = categoryNode == null || categoryNode.isNull() ? "" : categoryNode.asText("").trim();
            suggestion.setTags(tags);
            suggestion.setCategory(category);
            suggestion.setOk(true);
            suggestion.setMessage("以上仅为建议，尚未写入图库");
            return suggestion;
        } catch (Exception e) {
            suggestion.setOk(false);
            suggestion.setMessage("模型返回无法解析：" + abbreviate(raw));
            return suggestion;
        }
    }

    /** 每请求一个独立有界池：守护线程、有界队列、拒绝策略兜底（队列按单次上限留足，正常不会触发） */
    private ExecutorService newPool(int poolSize) {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                poolSize, poolSize, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, maxPerRequest)),
                runnable -> {
                    Thread thread = new Thread(runnable, "ai-tag-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static String joinOrPlaceholder(List<String> values) {
        return CollUtil.isEmpty(values) ? EMPTY_VOCABULARY : String.join("、", values);
    }

    private static String abbreviate(String raw) {
        String flat = raw.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }
}
