package com.lcl.yunpicturebackend.manager.ai;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lcl.yunpicturebackend.api.vision.AiVisionTagApi;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.vo.PictureAiTagSuggestionVO;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 打标管理器单测（T15）：**并发的四条硬契约**（有界并发 / 单张超时降级 / 单张失败不连坐 / 结果保序）
 * ＋ 提示词与词表注入 ＋ 模型输出的容错解析 ＋ **"只改标签与分类"的结构性不变量**。
 * <p>
 * 全部用桩（{@link AiVisionTagApi} 用 Mockito、无网络），进门禁；审查口径见 docs/plan.md T15 验收 ⑤。
 * </p>
 */
@DisplayName("AI 打标管理器")
class PictureAiTagManagerTest {

    private static final String OK_JSON = "{\"tags\":[\"高清\",\"生活\"],\"category\":\"素材\"}";

    private final AiVisionTagApi visionApi = mock(AiVisionTagApi.class);

    private PictureAiTagManager manager;

    @BeforeAll
    static void initTableInfo() {
        // LambdaUpdateWrapper 需要 MyBatis-Plus 的表信息缓存（Spring 上下文里由框架初始化）；
        // 纯单测里手工初始化一次，好让"SET 子句里只有 tags/category"这条不变量能在门禁里断言
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Picture.class);
    }

    @BeforeEach
    void setUp() {
        when(visionApi.isConfigured()).thenReturn(true);
        manager = newManager(3, 2_000, 8);
    }

    private PictureAiTagManager newManager(int concurrency, int timeoutMs, int maxPerRequest) {
        PictureAiTagManager created = new PictureAiTagManager(visionApi);
        ReflectionTestUtils.setField(created, "concurrency", concurrency);
        ReflectionTestUtils.setField(created, "timeoutMs", timeoutMs);
        ReflectionTestUtils.setField(created, "maxPerRequest", maxPerRequest);
        return created;
    }

    private static Picture picture(long id, String url) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setUrl(url);
        return picture;
    }

    private static List<Picture> pictures(int count) {
        List<Picture> pictures = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            pictures.add(picture(i, "https://cos.example.com/" + i + ".jpg"));
        }
        return pictures;
    }

    // ==================== 并发四条契约 ====================

    @Nested
    @DisplayName("并发（层次一）")
    class Concurrency {

        @Test
        @DisplayName("有界并发：6 张图、并发 3 ⇒ 峰值并发恰为 3（不会 6 张一起打）")
        void boundsConcurrency() throws Exception {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            when(visionApi.describeImage(anyString(), anyString())).thenAnswer(invocation -> {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                Thread.sleep(200);
                inFlight.decrementAndGet();
                return OK_JSON;
            });

            List<PictureAiTagSuggestionVO> suggestions = manager.suggest(pictures(6), List.of("高清"), List.of("素材"));

            assertThat(suggestions).hasSize(6);
            assertThat(suggestions).allMatch(PictureAiTagSuggestionVO::isOk);
            assertThat(peak.get()).as("峰值并发应恰好等于配置的并发度").isEqualTo(3);
        }

        @Test
        @DisplayName("结果保序：后发的先回，返回顺序仍与输入顺序一致")
        void keepsInputOrder() throws Exception {
            when(visionApi.describeImage(anyString(), anyString())).thenAnswer(invocation -> {
                String url = invocation.getArgument(0);
                // 第 1 张最慢、最后一张最快：完成顺序与输入顺序相反
                String index = url.substring(url.lastIndexOf('/') + 1, url.lastIndexOf('.'));
                Thread.sleep(index.equals("1") ? 300 : 30);
                return "{\"tags\":[\"t" + index + "\"],\"category\":\"c\"}";
            });

            List<PictureAiTagSuggestionVO> suggestions = manager.suggest(pictures(4), List.of(), List.of());

            assertThat(suggestions).extracting(PictureAiTagSuggestionVO::getPictureId)
                    .containsExactly(1L, 2L, 3L, 4L);
            assertThat(suggestions).extracting(s -> s.getTags().get(0))
                    .containsExactly("t1", "t2", "t3", "t4");
        }

        @Test
        @DisplayName("单张超时只降级那一张，其余照常返回")
        void degradesOnlyTimedOutPicture() {
            PictureAiTagManager fastTimeoutManager = newManager(3, 150, 8);
            when(visionApi.describeImage(anyString(), anyString())).thenAnswer(invocation -> {
                String url = invocation.getArgument(0);
                if (url.contains("2.jpg")) {
                    Thread.sleep(2_000);
                }
                return OK_JSON;
            });

            List<PictureAiTagSuggestionVO> suggestions =
                    fastTimeoutManager.suggest(pictures(3), List.of(), List.of());

            assertThat(suggestions).hasSize(3);
            assertThat(suggestions.get(1).isOk()).isFalse();
            assertThat(suggestions.get(1).getMessage()).contains("看图超时");
            assertThat(suggestions.get(0).isOk()).isTrue();
            assertThat(suggestions.get(2).isOk()).isTrue();
        }

        @Test
        @DisplayName("单张失败不连坐：一张抛异常，其余照常出建议")
        void isolatesFailure() {
            when(visionApi.describeImage(anyString(), anyString())).thenAnswer(invocation -> {
                String url = invocation.getArgument(0);
                if (url.contains("2.jpg")) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "看图接口返回异常（HTTP 401）");
                }
                return OK_JSON;
            });

            List<PictureAiTagSuggestionVO> suggestions = manager.suggest(pictures(3), List.of(), List.of());

            assertThat(suggestions).hasSize(3);
            assertThat(suggestions.get(0).isOk()).isTrue();
            assertThat(suggestions.get(1).isOk()).isFalse();
            assertThat(suggestions.get(1).getMessage()).contains("HTTP 401");
            assertThat(suggestions.get(2).isOk()).isTrue();
        }

        @Test
        @DisplayName("没有图片地址的图不发模型调用，直接降级该张")
        void skipsPicturesWithoutUrl() throws Exception {
            List<Picture> list = pictures(2);
            list.add(picture(3, "   "));
            when(visionApi.describeImage(anyString(), anyString())).thenReturn(OK_JSON);

            List<PictureAiTagSuggestionVO> suggestions = manager.suggest(list, List.of(), List.of());

            verify(visionApi, times(2)).describeImage(anyString(), anyString());
            assertThat(suggestions.get(2).isOk()).isFalse();
            assertThat(suggestions.get(2).getMessage()).contains("没有可访问的图片地址");
        }
    }

    // ==================== 提示词与输入校验 ====================

    @Nested
    @DisplayName("提示词与上限")
    class PromptAndLimits {

        @Test
        @DisplayName("提示词注入当前词表，并要求优先复用、只输出一行 JSON")
        void injectsVocabulary() throws Exception {
            when(visionApi.describeImage(anyString(), anyString())).thenReturn(OK_JSON);

            manager.suggest(pictures(1), List.of("高清", "生活"), List.of("素材", "海报"));

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(visionApi).describeImage(anyString(), promptCaptor.capture());
            String prompt = promptCaptor.getValue();
            assertThat(prompt).contains("高清、生活").contains("素材、海报").contains("优先复用")
                    .contains("{\"tags\":[\"标签1\",\"标签2\"],\"category\":\"分类\"}");
        }

        @Test
        @DisplayName("词表为空时提示词给出占位，不留空白")
        void placeholderForEmptyVocabulary() throws Exception {
            when(visionApi.describeImage(anyString(), anyString())).thenReturn(OK_JSON);

            manager.suggest(pictures(1), List.of(), List.of());

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(visionApi).describeImage(anyString(), promptCaptor.capture());
            assertThat(promptCaptor.getValue()).contains("（暂无）");
        }

        @Test
        @DisplayName("超过单次上限即拒，且一次模型调用都不发")
        void rejectsTooManyPictures() {
            PictureAiTagManager smallManager = newManager(3, 2_000, 2);

            assertThatThrownBy(() -> smallManager.suggest(pictures(3), List.of(), List.of()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("单次最多 2 张");
            verify(visionApi, times(0)).describeImage(anyString(), anyString());
        }

        @Test
        @DisplayName("空列表即拒")
        void rejectsEmptyList() {
            assertThatThrownBy(() -> manager.suggest(List.of(), List.of(), List.of()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("请先选择");
        }
    }

    // ==================== 解析与清洗 ====================

    @Nested
    @DisplayName("模型输出解析与清洗")
    class Parsing {

        @Test
        @DisplayName("容错剥掉 Markdown 代码块与前后杂文本")
        void toleratesCodeFenceAndNoise() {
            Picture picture = picture(1, "https://cos.example.com/1.jpg");

            PictureAiTagSuggestionVO fenced = PictureAiTagManager.parse(picture,
                    picture.getUrl(), "这是结果：\n```json\n" + OK_JSON + "\n```\n以上。");

            assertThat(fenced.isOk()).isTrue();
            assertThat(fenced.getTags()).containsExactly("高清", "生活");
            assertThat(fenced.getCategory()).isEqualTo("素材");
        }

        @Test
        @DisplayName("无法解析 / 空返回 ⇒ 该张失败（不猜标签）")
        void failsOnUnparsableOutput() {
            Picture picture = picture(1, "https://cos.example.com/1.jpg");

            assertThat(PictureAiTagManager.parse(picture, picture.getUrl(), "没有 JSON").isOk()).isFalse();
            assertThat(PictureAiTagManager.parse(picture, picture.getUrl(), "  ").isOk()).isFalse();
        }

        @Test
        @DisplayName("分类缺失或为 null ⇒ 空串（调用方据此不写分类）")
        void handlesMissingCategory() {
            Picture picture = picture(1, "https://cos.example.com/1.jpg");

            PictureAiTagSuggestionVO suggestion =
                    PictureAiTagManager.parse(picture, picture.getUrl(), "{\"tags\":[\"高清\"],\"category\":null}");

            assertThat(suggestion.isOk()).isTrue();
            assertThat(suggestion.getCategory()).isEmpty();
        }

        @Test
        @DisplayName("清洗：转义 HTML、去空、去重；超长即拒")
        void sanitizesTags() {
            assertThat(PictureAiTagManager.sanitizeTags(List.of(" 高清 ", "<b>生活</b>", "高清", "  ", "")))
                    .containsExactly("高清", "生活");
            assertThat(PictureAiTagManager.sanitizeTags(null)).isEmpty();
            assertThatThrownBy(() -> PictureAiTagManager.sanitizeTags(List.of("长".repeat(33))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("标签过长");
            assertThatThrownBy(() -> PictureAiTagManager.sanitizeCategory("长".repeat(65)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("分类过长");
        }
    }

    // ==================== 最要紧的不变量 ====================

    @Nested
    @DisplayName("落库只改标签与分类")
    class ApplyInvariant {

        @Test
        @DisplayName("更新条件里只有 tags/category：审核四字段结构性不可能被写到")
        void updateWrapperNeverTouchesReviewFields() {
            LambdaUpdateWrapper<Picture> update = Wrappers.<Picture>lambdaUpdate().eq(Picture::getId, 1L);

            PictureAiTagManager.applyTo(update, List.of("高清", "生活"), "素材");

            String sqlSet = update.getSqlSet();
            assertThat(sqlSet).contains("tags").contains("category");
            assertThat(sqlSet)
                    .as("审核状态/审核人/审核留言/审核时间绝不能进 SET 子句")
                    .doesNotContain("review_status").doesNotContain("reviewer_id")
                    .doesNotContain("review_message").doesNotContain("review_time");
        }

        @Test
        @DisplayName("空建议不写：不拿「模型没给出」去清掉已有标签或分类")
        void blankSuggestionWritesNothing() {
            LambdaUpdateWrapper<Picture> update = Wrappers.<Picture>lambdaUpdate().eq(Picture::getId, 1L);

            PictureAiTagManager.applyTo(update, List.of(), "");

            assertThat(update.getSqlSet()).as("空值应不产生任何 SET").isNull();
        }

        @Test
        @DisplayName("标签按 JSON 数组字符串落库（与既有编辑接口同口径）")
        void writesTagsAsJsonArray() {
            LambdaUpdateWrapper<Picture> update = Wrappers.<Picture>lambdaUpdate().eq(Picture::getId, 1L);

            PictureAiTagManager.applyTo(update, List.of("高清"), "");

            assertThat(update.getParamNameValuePairs().values()).contains("[\"高清\"]");
        }
    }
}
