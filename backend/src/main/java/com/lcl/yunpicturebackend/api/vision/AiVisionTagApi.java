package com.lcl.yunpicturebackend.api.vision;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.observability.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模态看图接口（OpenAI 兼容协议，AI 打标用）。
 * <p>
 * 口径与引擎侧 T6 一致：**base-url 是协议根**（例 {@code https://api.xiaomimimo.com}），
 * {@code /v1/chat/completions} 由本类拼——换厂商若把 base-url 配成带 {@code /v1} 的形态会 404（F12 已知限制 7）。
 * 密钥走环境变量（{@code app.ai.vision.*}），**不入库**。
 * </p>
 * <p>
 * 本类只负责"一张图 → 一段文本"，逐张超时、并发上限与容错解析都在
 * {@link com.lcl.yunpicturebackend.manager.ai.PictureAiTagManager}。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiVisionTagApi {

    /** 协议路径：base-url 之后固定这一段 */
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final ObjectMapper objectMapper;

    @Value("${app.ai.vision.base-url:}")
    private String baseUrl;

    @Value("${app.ai.vision.api-key:}")
    private String apiKey;

    @Value("${app.ai.vision.model:}")
    private String model;

    /** 单张看图的连接/读取超时（毫秒）：单张耗时上界，管理器另按同一上界逐张计时 */
    @Value("${app.ai.vision.timeout-ms:45000}")
    private int timeoutMs;

    /** 三项缺一即视为未配置：调用方据此给出明确文案，而不是发一次注定 401 的请求 */
    public boolean isConfigured() {
        return StrUtil.isNotBlank(baseUrl) && StrUtil.isNotBlank(apiKey) && StrUtil.isNotBlank(model);
    }

    /**
     * 让多模态模型看一张图并返回文本回复。
     *
     * @param imageUrl 图片地址（图库 COS 地址；厂商需能直接抓取）
     * @param prompt   提示词（含词表与输出格式约束）
     * @return 模型回复原文（可能带 Markdown 代码块，由调用方容错解析）
     */
    public String describeImage(String imageUrl, String prompt) {
        if (!isConfigured()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "AI 打标模型未配置：请设置 app.ai.vision.{base-url,api-key,model}");
        }
        HttpRequest httpRequest = HttpRequest.post(baseUrl + CHAT_COMPLETIONS_PATH)
                .header(Header.AUTHORIZATION, "Bearer " + apiKey)
                .header(Header.CONTENT_TYPE, ContentType.JSON.getValue())
                .header(TraceContext.TRACE_ID_HEADER, TraceContext.currentOrDefault())
                .timeout(timeoutMs)
                .body(requestBody(imageUrl, prompt));
        try (HttpResponse httpResponse = httpRequest.execute()) {
            String responseBody = httpResponse.body();
            if (!httpResponse.isOk()) {
                // 厂商原文只进日志：面向管理员的文案里不放响应体，避免把上游细节带到页面上
                log.error("看图接口返回异常，status={}, body={}", httpResponse.getStatus(), responseBody);
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "看图接口返回异常（HTTP " + httpResponse.getStatus() + "）");
            }
            String content = contentOf(responseBody);
            if (StrUtil.isBlank(content)) {
                log.error("看图接口响应缺少 content：{}", responseBody);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "看图接口响应缺少 content");
            }
            return content;
        }
    }

    /** OpenAI 兼容请求体：一条 user 消息里同时带文本与图片地址 */
    private String requestBody(String imageUrl, String prompt) {
        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", Map.of("url", imageUrl));
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(textPart, imagePart));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(message));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "构造看图请求体失败");
        }
    }

    /** 取 choices[0].message.content；结构不符返回 null（由调用方报"缺少 content"） */
    private String contentOf(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody)
                    .path("choices").path(0).path("message").path("content");
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            log.error("看图接口响应无法解析：{}", responseBody);
            return null;
        }
    }
}
