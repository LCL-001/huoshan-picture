package com.lcl.yunpicturebackend.manager.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.config.AiAssistantProperties;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 图库后端 AI 代理（T10）：把一次已鉴权的对话请求转成一条到引擎（ai/agent）的 SSE 流。
 * <p>
 * 对端契约见 docs/plans/2026-09-14-档2-用户可见MVP实施计划.md：引擎端点
 * {@code GET {engine-base-url}/ai/huoshan/chat}，需服务间 API key 与凭据组（satoken 头 + SESSION Cookie）。
 * 凭据只在本次调用内流转，不落库、不进日志。
 * <p>
 * 实现选型（设计文档 L110 留的"实施时定"）：{@link SseEmitter} + JDK {@link HttpURLConnection}
 * + 专用有界守护线程池，零新增依赖。
 */
@Slf4j
@Component
public class AiAssistantProxyManager {

    /** 与引擎 BaseAgent 的 SseEmitter 超时（300s）对齐 */
    private static final long EMITTER_TIMEOUT_MS = 300_000L;

    /** 引擎正常响应的事件流类型；不是它即视为错误响应（引擎的 401/40000/50000 都是 JSON） */
    private static final String SSE_CONTENT_TYPE = "text/event-stream";

    private static final String ENGINE_CHAT_PATH = "/ai/huoshan/chat";
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String SATOKEN_HEADER = "satoken";
    private static final String SESSION_COOKIE_NAME = "SESSION";
    private static final String DATA_PREFIX = "data:";
    private static final String DONE_FLAG = "[DONE]";
    private static final int ERROR_BODY_MAX_CHARS = 2048;
    private static final int ERROR_TEXT_MAX_CHARS = 200;

    private final AiAssistantProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public AiAssistantProxyManager(AiAssistantProperties properties,
                                   ObjectMapper objectMapper,
                                   @Qualifier("aiAssistantExecutor") ExecutorService executor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * 单点定义回给浏览器的事件流载体与超时。抽成方法是为了让测试能在"不绑 servlet 响应"的前提下
     * 捕获实际发出的帧（覆写它返回记录型 emitter），生产路径每请求都会走到。
     */
    protected SseEmitter createEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MS);
    }

    /**
     * 打开一条到引擎的 SSE 流并以 {@link SseEmitter} 返回浏览器。
     *
     * @param message   用户本轮提问
     * @param userId    图库登录用户 id（引擎映射为 {@code huoshan:<userId>} 的会话归属）
     * @param chatId    对话串标识，可为空（空 = 引擎新起一条会话）
     * @param satoken   调用者 sa-token（原样透传，由图库服务端判 RBAC）
     * @param sessionId 调用者 Spring Session 会话 id（空间接口需要它）
     */
    public SseEmitter chat(String message, Long userId, String chatId, String satoken, String sessionId) {
        ForwardRequest request = new ForwardRequest(
                requireConfigured(properties.getEngineBaseUrl(), "图库助手引擎地址未配置"),
                requireConfigured(properties.getInternalApiKey(), "图库助手引擎密钥未配置"),
                message, userId, chatId, satoken, sessionId);

        SseEmitter emitter = createEmitter();
        AtomicReference<HttpURLConnection> upstream = new AtomicReference<>();
        // 浏览器断流/出错/超时即断开到引擎的连接：代理侧的上游由我们掌控，能干净关掉。
        // （与 decisions.md 挂在档 2 之后的 R1——引擎侧 provider 连接因 Flux.cache(0) 取消不断开——不是同一回事）
        emitter.onCompletion(() -> close(upstream));
        emitter.onError(e -> close(upstream));
        emitter.onTimeout(() -> close(upstream));

        try {
            executor.execute(() -> forward(emitter, upstream, request));
        } catch (RejectedExecutionException e) {
            log.warn("AI 助手转发线程池已满，拒绝本次对话请求");
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void forward(SseEmitter emitter, AtomicReference<HttpURLConnection> upstream, ForwardRequest request) {
        try {
            HttpURLConnection connection = open(request);
            upstream.set(connection);
            int status = connection.getResponseCode();
            String contentType = connection.getContentType();
            if (status != HttpURLConnection.HTTP_OK || contentType == null || !contentType.contains(SSE_CONTENT_TYPE)) {
                sendErrorAsAnswer(emitter, describeUpstreamError(status, connection));
                return;
            }
            if (!relay(emitter, connection.getInputStream())) {
                sendErrorAsAnswer(emitter, "图库助手响应意外中断，请重试");
            }
        } catch (IOException e) {
            log.warn("AI 助手转发失败: {}", e.getMessage());
            sendErrorAsAnswer(emitter, "图库助手引擎暂时不可用，请稍后重试");
        } finally {
            close(upstream);
        }
    }

    private HttpURLConnection open(ForwardRequest request) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(chatUrl(request)).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty(INTERNAL_API_KEY_HEADER, request.apiKey());
        connection.setRequestProperty(SATOKEN_HEADER, request.satoken());
        connection.setRequestProperty("Cookie", SESSION_COOKIE_NAME + "=" + request.sessionId());
        connection.setRequestProperty("Accept", SSE_CONTENT_TYPE);
        connection.setConnectTimeout(toMillis(properties.getConnectTimeout()));
        connection.setReadTimeout(toMillis(properties.getReadTimeout()));
        return connection;
    }

    private String chatUrl(ForwardRequest request) {
        StringBuilder url = new StringBuilder(StrUtil.removeSuffix(request.baseUrl(), "/"));
        url.append(ENGINE_CHAT_PATH)
                .append("?message=").append(encode(request.message()))
                .append("&userId=").append(request.userId());
        if (StrUtil.isNotBlank(request.chatId())) {
            url.append("&chatId=").append(encode(request.chatId()));
        }
        return url.toString();
    }

    /**
     * 中继引擎的事件流：逐帧原样转发 {@code data:} 负载，遇 {@code [DONE]} 收尾并返回 true。
     * 多行 data 按 SSE 规范以 {@code \n} 拼接；其余字段（event:/id:/retry:/注释）引擎当前不发，忽略。
     */
    private boolean relay(SseEmitter emitter, InputStream body) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder frame = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    if (line.startsWith(DATA_PREFIX)) {
                        if (frame.length() > 0) {
                            frame.append('\n');
                        }
                        frame.append(dataOf(line));
                    }
                    continue;
                }
                if (frame.length() == 0) {
                    continue;
                }
                String payload = frame.toString();
                frame.setLength(0);
                if (DONE_FLAG.equals(payload)) {
                    emitter.send(DONE_FLAG, MediaType.TEXT_PLAIN);
                    emitter.complete();
                    return true;
                }
                emitter.send(payload, MediaType.TEXT_PLAIN);
            }
        }
        return false;
    }

    /** SSE 规范：冒号后紧跟的一个空格属于分隔符，不算数据 */
    private String dataOf(String line) {
        String data = line.substring(DATA_PREFIX.length());
        return data.startsWith(" ") ? data.substring(1) : data;
    }

    /**
     * 把失败合成一条 {@code answer} 事件 + {@code [DONE]} 后收尾。
     * 代理不引入新的 SSE 事件类型（event 协议仍只有 step/answer/metrics/[DONE]），
     * 也不给引擎加契约；这样前端能拿到人能读的原因，而不是"连接断开"。
     */
    private void sendErrorAsAnswer(SseEmitter emitter, String text) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "answer");
            payload.put("content", text);
            emitter.send(objectMapper.writeValueAsString(payload), MediaType.TEXT_PLAIN);
            emitter.send(DONE_FLAG, MediaType.TEXT_PLAIN);
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开时收尾必然失败，属正常路径（连接已废弃，无处上报）
            log.debug("AI 助手事件收尾失败，客户端可能已断开");
        } finally {
            emitter.complete();
        }
    }

    private String describeUpstreamError(int status, HttpURLConnection connection) {
        String message = extractMessage(readErrorBody(connection));
        if (message != null) {
            return "图库助手引擎拒绝了本次请求：" + message;
        }
        return "图库助手引擎响应异常（HTTP " + status + "）";
    }

    /** 读引擎的错误响应体：401 等错误状态要走 errorStream，200 但非 SSE 的错误体在 inputStream */
    private String readErrorBody(HttpURLConnection connection) {
        InputStream stream = connection.getErrorStream();
        if (stream == null) {
            try {
                stream = connection.getInputStream();
            } catch (IOException e) {
                return null;
            }
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && body.length() < ERROR_BODY_MAX_CHARS) {
                body.append(line);
            }
            return body.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** 从引擎错误 JSON 里取 message；不是 JSON 时退回截断原文，保证仍然可读 */
    private String extractMessage(String body) {
        if (StrUtil.isBlank(body)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body).path("message");
            if (!node.isMissingNode() && StrUtil.isNotBlank(node.asText())) {
                return node.asText();
            }
        } catch (IOException e) {
            // 非 JSON 响应体：走下面的截断兜底
        }
        return body.length() > ERROR_TEXT_MAX_CHARS ? body.substring(0, ERROR_TEXT_MAX_CHARS) + "…" : body;
    }

    private String requireConfigured(String value, String message) {
        ThrowUtils.throwIf(StrUtil.isBlank(value), ErrorCode.SYSTEM_ERROR, message);
        return value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 0 与负数表示不限制，与 JDK 超时语义一致 */
    private int toMillis(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return 0;
        }
        return (int) Math.min(duration.toMillis(), Integer.MAX_VALUE);
    }

    private void close(AtomicReference<HttpURLConnection> upstream) {
        HttpURLConnection connection = upstream.getAndSet(null);
        if (connection != null) {
            connection.disconnect();
        }
    }

    /** 一次转发所需的全部入参（凭据只在请求生命周期内存流转，不落库、不进日志） */
    private record ForwardRequest(String baseUrl, String apiKey, String message, Long userId,
                                  String chatId, String satoken, String sessionId) {
    }
}
