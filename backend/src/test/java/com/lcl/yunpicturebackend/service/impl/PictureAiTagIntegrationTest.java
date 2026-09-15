package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.lang.UUID;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureAiTagApplyRequest;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureAiTagRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureAiTagSuggestionVO;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 打标的**真实 SQL 效果**验证（T15 验收 ②③④）：单测只能断言"更新条件里没有审核字段"，
 * 这里跑真正的 MySQL，逐字段核对写完之后审核四字段**确实**没变、且公共图库的图没有被"打回待审核"。
 * <p>
 * 桩模型是进程内 JDK HttpServer（经 {@code @DynamicPropertySource} 注入 app.ai.vision.*），
 * 因此不依赖外部厂商；但需要 MySQL（本机常驻），按既有约定以 IntegrationTest 结尾、不进 CI/门禁。
 * 测试自建**唯一**标签/分类词并硬删收尾（含 picture 行的物理删除），不留探针数据。
 * </p>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI 打标（真实库）")
class PictureAiTagIntegrationTest {

    private static final String TAG_A = "ai-打标-验证A-" + UUID.fastUUID().toString(true).substring(0, 6);
    private static final String TAG_B = "ai-打标-验证B-" + UUID.fastUUID().toString(true).substring(0, 6);
    private static final String CATEGORY = "ai-分类-验证-" + UUID.fastUUID().toString(true).substring(0, 6);
    private static final long FAKE_ADMIN_ID = 999_999_001L;
    private static final long FAKE_REVIEWER_ID = 999_999_002L;
    private static final String REVIEW_MESSAGE = "人工审核通过（集成测试）";

    private static HttpServer visionStub;
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    @Autowired
    private IPictureService pictureService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void visionProperties(DynamicPropertyRegistry registry) throws IOException {
        visionStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        visionStub.createContext("/", PictureAiTagIntegrationTest::handleVision);
        visionStub.setExecutor(Executors.newCachedThreadPool());
        visionStub.start();
        registry.add("app.ai.vision.base-url", () -> "http://127.0.0.1:" + visionStub.getAddress().getPort());
        registry.add("app.ai.vision.api-key", () -> "integration-vision-key");
        registry.add("app.ai.vision.model", () -> "stub-vision-model");
        registry.add("app.ai.vision.concurrency", () -> "2");
        registry.add("app.ai.vision.timeout-ms", () -> "5000");
    }

    @AfterAll
    void stopStub() {
        if (visionStub != null) {
            visionStub.stop(0);
        }
    }

    /** 桩模型：固定返回含本次唯一词的建议 */
    private static void handleVision(HttpExchange exchange) throws IOException {
        MODEL_CALLS.incrementAndGet();
        byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"{\\\"tags\\\":[\\\"" + TAG_A + "\\\",\\\""
                + TAG_B + "\\\"],\\\"category\\\":\\\"" + CATEGORY + "\\\"}\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    @DisplayName("出建议不写库；应用后只改 tags/category，审核四字段逐字段不变")
    void suggestIsReadOnlyAndApplyKeepsReviewFields() {
        Date reviewTime = new Date(System.currentTimeMillis() / 1000 * 1000);
        Picture picture = new Picture();
        picture.setUrl("https://cos.example.com/ai-tag-it-" + UUID.fastUUID().toString(true) + ".jpg");
        picture.setName("ai-tag-it");
        picture.setUserId(FAKE_ADMIN_ID);
        picture.setSpaceId(null);
        picture.setReviewStatus(1);
        picture.setReviewerId(FAKE_REVIEWER_ID);
        picture.setReviewMessage(REVIEW_MESSAGE);
        picture.setReviewTime(reviewTime);
        picture.setTags(null);
        picture.setCategory(null);
        pictureService.save(picture);
        Long pictureId = picture.getId();
        try {
            User admin = new User();
            admin.setId(FAKE_ADMIN_ID);

            // ① 出建议：只读——库里的 tags/category 仍为空
            PictureAiTagRequest suggestRequest = new PictureAiTagRequest();
            suggestRequest.setPictureIdList(List.of(pictureId));
            List<PictureAiTagSuggestionVO> suggestions = pictureService.suggestAiTags(suggestRequest, admin);
            assertThat(suggestions).hasSize(1);
            assertThat(suggestions.get(0).isOk()).isTrue();
            assertThat(suggestions.get(0).getTags()).containsExactly(TAG_A, TAG_B);
            assertThat(suggestions.get(0).getCategory()).isEqualTo(CATEGORY);
            Picture afterSuggest = pictureService.getById(pictureId);
            assertThat(afterSuggest.getTags()).as("出建议不得写库").isNull();
            assertThat(afterSuggest.getCategory()).isNull();

            // ② 应用建议
            int usageBeforeA = usageCount(TAG_A);
            PictureAiTagApplyRequest applyRequest = new PictureAiTagApplyRequest();
            PictureAiTagApplyRequest.Item item = new PictureAiTagApplyRequest.Item();
            item.setPictureId(pictureId);
            item.setTags(suggestions.get(0).getTags());
            item.setCategory(suggestions.get(0).getCategory());
            applyRequest.setItems(List.of(item));
            List<PictureAiTagSuggestionVO> applied = pictureService.applyAiTags(applyRequest, admin);
            assertThat(applied).hasSize(1);
            assertThat(applied.get(0).isOk()).isTrue();

            // ③ 标签/分类写进去了，审核四字段逐字段不变（最要紧的一条）
            Picture afterApply = pictureService.getById(pictureId);
            assertThat(afterApply.getTags()).isEqualTo("[\"" + TAG_A + "\",\"" + TAG_B + "\"]");
            assertThat(afterApply.getCategory()).isEqualTo(CATEGORY);
            assertThat(afterApply.getReviewStatus()).as("审核状态不得被打回").isEqualTo(1);
            assertThat(afterApply.getReviewerId()).as("审核人不得被覆盖").isEqualTo(FAKE_REVIEWER_ID);
            assertThat(afterApply.getReviewMessage()).as("审核留言不得被覆盖").isEqualTo(REVIEW_MESSAGE);
            assertThat(afterApply.getReviewTime()).as("审核时间不得被改写").isEqualTo(reviewTime);

            // ④ 词表 usageCount：本批去重后各计一次（与 editPictureByBatch 同口径）
            assertThat(usageCount(TAG_A)).isEqualTo(usageBeforeA + 1);
            assertThat(usageCount(TAG_B)).isEqualTo(1);
            assertThat(usageCount(CATEGORY)).isEqualTo(1);
        } finally {
            // 硬删收尾：picture 有 @TableLogic（removeById 只是逻辑删），用 SQL 物理删干净；词表词条一并删掉
            jdbcTemplate.update("DELETE FROM picture WHERE id = ?", pictureId);
            jdbcTemplate.update("DELETE FROM tag WHERE name IN (?, ?, ?)", TAG_A, TAG_B, CATEGORY);
            assertThat(pictureService.getById(pictureId)).isNull();
        }
    }

    /** 词条还没进过词表时视为 0（新词条由 upsertVocabulary 在应用建议时创建） */
    private int usageCount(String name) {
        List<Integer> counts = jdbcTemplate.queryForList(
                "SELECT usageCount FROM tag WHERE name = ? ORDER BY id DESC", Integer.class, name);
        return counts.isEmpty() ? 0 : counts.get(0);
    }
}
