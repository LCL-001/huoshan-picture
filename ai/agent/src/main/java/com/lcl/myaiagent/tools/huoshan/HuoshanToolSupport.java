package com.lcl.myaiagent.tools.huoshan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

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
            return errorJson(-1, "调用图库接口失败：" + e.getMessage());
        }
    }

    private static String errorJson(int code, String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", true);
        node.put("code", code);
        node.put("message", message);
        return node.toString();
    }
}
