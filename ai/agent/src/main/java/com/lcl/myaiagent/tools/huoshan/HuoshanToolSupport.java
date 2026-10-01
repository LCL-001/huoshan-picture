package com.lcl.myaiagent.tools.huoshan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lcl.myaiagent.tools.huoshan.dto.PrepareOperationResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * 工具返回渲染（T7 档 1）：成功返回结构化 JSON；图库业务错误（登录态失效 / 无权限 / 参数错）
 * 转成结构化错误 JSON 交给模型与前端，而不是抛异常打断这一步——模型据此能改口径重试，
 * 或明确告诉用户需要重新登录。
 */
@Slf4j
final class HuoshanToolSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HuoshanToolSupport() {
    }

    static <T> String render(Supplier<T> call) {
        try {
            return MAPPER.writeValueAsString(call.get());
        } catch (HuoshanApiException e) {
            log.warn("图库工具调用返回业务错误, code={}, message={}", e.getCode(), e.getMessage());
            return errorJson(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("图库工具调用异常", e);
            return errorJson(-1, "调用图库接口失败，请稍后重试");
        }
    }

    /**
     * prepare 端点返回的 {operationId, operationType, digest, summary} → 待确认操作结果
     * （T27.6 两个 prepare 工具共用）。note 是给模型的转述指引：如实告知"待确认、未改动"，
     * 防止模型把提交当成已执行。
     * 卡片摘要（T27.10）由后端从既存计划生成，这里只透传——后端响应缺 summary 视为版本不一致，
     * fail-closed 报错，绝不自算摘要兜底（自算会重新打开"卡片 ≠ 计划"的漂移面）。
     */
    static PrepareOperationResult pendingConfirmation(String operationType, JsonNode data) {
        PrepareOperationResult result = new PrepareOperationResult();
        result.setStatus(PrepareOperationResult.STATUS_PENDING_CONFIRMATION);
        result.setOperationType(operationType);
        result.setOperationId(data.path("operationId").asText(null));
        result.setDigest(data.path("digest").asText(null));
        List<String> summary = readSummaryLines(data.path("summary"));
        if (summary.isEmpty()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "图库后端未返回卡片摘要，无法生成待确认操作（前后端版本可能不一致）");
        }
        result.setSummary(summary);
        result.setNote("这是待确认操作：还没有改动任何数据。请提醒用户核对确认卡片内容并点击确认后才会执行，确认结果会展示在确认卡片上；不要重复提交同一操作。");
        return result;
    }

    private static List<String> readSummaryLines(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                lines.add(item.asText());
            }
        }
        return lines;
    }

    private static String errorJson(int code, String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", true);
        node.put("code", code);
        node.put("message", message);
        return node.toString();
    }

    /**
     * 清洗字符串列表（档 3 写类工具的 id / 标签 / URL 共用）：去空白、剥掉包裹引号、丢空项、按首次出现去重。
     * <p>
     * 模型偶尔会把同一个 id 报两遍、把值写成带空格的样式，或者**带着引号**送过来——
     * 后者是上游的既有形态：MCP 工具（searchImage）返回的是一整个字符串，Spring AI 按 JSON 序列化后
     * 外层就带一对引号（实测 `"https://...jpeg,https://...jpeg"`）。在工具侧剥掉引号，比指望模型每次都剥更稳。
     * </p>
     */
    static List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String stripped = stripWrappingQuotes(value.trim());
            if (!stripped.isEmpty()) {
                cleaned.add(stripped);
            }
        }
        return new ArrayList<>(cleaned);
    }

    /** 去掉成对的包裹引号（" / ' / `），可能套多层 */
    private static String stripWrappingQuotes(String value) {
        String result = value;
        while (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            boolean paired = (first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '`' && last == '`');
            if (!paired) {
                break;
            }
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }
}
