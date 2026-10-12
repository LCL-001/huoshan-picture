package com.lcl.yunpicturebackend.controller;

import com.lcl.yunpicturebackend.annotation.RateLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高成本/敏感端点的限流覆盖守护（T49）。
 * <p>
 * 与 {@code PictureAiTagAdminGuardTest} 同一手法——注解被摘掉不会有编译错误，只有这条测试会红。
 * 覆盖对象就是 spec「限流不做：全站 blanket 限流」里点名的那些端点：会调外部付费服务（扩图 / 视觉打标 /
 * 以图搜图 / 批量抓取 / URL 与文件上传）或可被用户滥用的助手端点（对话 / 取票 / prepare / confirm）。
 * </p>
 */
@DisplayName("限流覆盖守护")
class RateLimitCoverageGuardTest {

    @Test
    @DisplayName("高成本/敏感端点必须带 @RateLimit 且额度为正")
    void expensiveEndpointsCarryRateLimit() {
        assertRateLimit(PictureController.class, "uploadPicture", "picture:upload");
        assertRateLimit(PictureController.class, "uploadPicture", "picture:upload:url");
        assertRateLimit(PictureController.class, "uploadPictureByBatch", "picture:upload:batch");
        assertRateLimit(PictureController.class, "createOutPaintingTask", "picture:outpainting");
        assertRateLimit(PictureController.class, "suggestAiTags", "picture:aitag:suggest");
        assertRateLimit(PictureController.class, "applyAiTags", "picture:aitag:apply");
        assertRateLimit(PictureController.class, "searchPictureByPicture", "picture:search:image");
        assertRateLimit(PictureController.class, "searchPictureByColor", "picture:search:color");

        assertRateLimit(AiAssistantController.class, "issueTicket", "ai:ticket");
        assertRateLimit(AiAssistantController.class, "chat", "ai:chat");
        assertRateLimit(AiAssistantController.class, "prepareOperation", "ai:prepare");
        assertRateLimit(AiAssistantController.class, "confirmOperation", "ai:confirm");

        assertRateLimit(FileController.class, "uploadFile", "file:upload");
    }

    private void assertRateLimit(Class<?> type, String methodName, String expectedKey) {
        // 同名重载（uploadPicture 有 multipart 与 url 两个）——任一带期望 key 即算覆盖
        boolean covered = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .map(m -> m.getAnnotation(RateLimit.class))
                .filter(Objects::nonNull)
                .anyMatch(rl -> expectedKey.equals(rl.key()) && rl.limit() > 0 && rl.windowSeconds() > 0);
        assertThat(covered)
                .as("%s#%s 必须带 @RateLimit(key=%s) 且额度为正（注解被摘掉不会有编译错误，只有这条会红）",
                        type.getSimpleName(), methodName, expectedKey)
                .isTrue();
    }
}
