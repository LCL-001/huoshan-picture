package com.lcl.yunpicturebackend.manager.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.config.AiAssistantProperties;
import com.lcl.yunpicturebackend.domain.dto.ai.AiOperationResultFact;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** T17：调用者角色，引擎按它决定本次会话挂不挂看图打标工具（缺省即非管理员） */
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final String DATA_PREFIX = "data:";
    private static final String DONE_FLAG = "[DONE]";
    private static final int ERROR_BODY_MAX_CHARS = 2048;

    private final AiAssistantProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final String tokenName;
    private final String sessionCookieName;

    public AiAssistantProxyManager(AiAssistantProperties properties,
                                   ObjectMapper objectMapper,
                                   @Qualifier("aiAssistantExecutor") ExecutorService executor,
                                   @Value("${sa-token.token-name:satoken}") String tokenName,
                                   @Value("${server.servlet.session.cookie.name:SESSION}") String sessionCookieName) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.tokenName = tokenName;
        this.sessionCookieName = sessionCookieName;
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
     * @param message       用户本轮提问
     * @param userId        图库登录用户 id（引擎映射为 {@code huoshan:<userId>} 的会话归属）
     * @param chatId        对话串标识，可为空（空 = 引擎新起一条会话）
     * @param satoken       调用者 sa-token（原样透传，由图库服务端判 RBAC）
     * @param sessionCookie 调用者 Spring Session 会话 Cookie 的**原始值**（空间接口需要它；
     *                      注意是 Cookie 值本身，不是 session.getId()——两者在 Base64 编码下不等价）
     * @param userRole      调用者角色（T17：引擎按它裁剪工具集，只有管理员挂看图打标工具）。
     *                      这只影响"引擎给模型看哪些工具"，权限判定仍全部落在图库服务端
     */
    public SseEmitter chat(String message, Long userId, String chatId, String satoken, String sessionCookie,
                           String userRole) {
        ForwardRequest request = new ForwardRequest(
                requireConfigured(properties.getEngineBaseUrl(), "图库助手引擎地址未配置"),
                requireConfigured(properties.getInternalApiKey(), "图库助手引擎密钥未配置"),
                message, userId, chatId, satoken, sessionCookie, userRole);

        SseEmitter emitter = createEmitter();
        Upstream upstream = new Upstream();
        // 浏览器断流/出错/超时即断开到引擎的连接：代理侧的上游由我们掌控，能干净关掉。
        // （与 decisions.md 挂在档 2 之后的 R1——引擎侧 provider 连接因 Flux.cache(0) 取消不断开——不是同一回事）
        emitter.onCompletion(upstream::cancel);
        emitter.onError(e -> upstream.cancel());
        emitter.onTimeout(upstream::cancel);

        try {
            executor.execute(() -> forward(emitter, upstream, request));
        } catch (RejectedExecutionException e) {
            log.warn("AI 助手转发线程池已满，拒绝本次对话请求");
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void forward(SseEmitter emitter, Upstream upstream, ForwardRequest request) {
        // R4：取消可能发生在 worker 启动之前（客户端早断），此时连接还没登记——先看一眼，别白建连
        if (upstream.isCancelled()) {
            log.debug("AI 助手客户端已断开，本次转发不再建连");
            return;
        }
        try {
            HttpURLConnection connection = open(request);
            // 登记后复查取消位：取消恰好落在"建连与登记之间"时，这里立刻断开且不进入请求/中继
            if (!upstream.register(connection)) {
                log.debug("AI 助手客户端在建立连接期间断开，已关闭上游连接、不发起请求");
                return;
            }
            int status = connection.getResponseCode();
            String contentType = connection.getContentType();
            if (status != HttpURLConnection.HTTP_OK || contentType == null || !contentType.contains(SSE_CONTENT_TYPE)) {
                sendErrorAsError(emitter, describeUpstreamError(status, connection));
                return;
            }
            if (!relay(emitter, connection.getInputStream())) {
                sendErrorAsError(emitter, "图库助手响应意外中断，请重试");
            }
        } catch (IOException e) {
            log.warn("AI 助手转发失败: {}", e.getMessage());
            sendErrorAsError(emitter, "助手暂时不可用，请稍后重试");
        } finally {
            upstream.disconnect();
        }
    }

    private HttpURLConnection open(ForwardRequest request) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(chatUrl(request)).openConnection();
        connection.setRequestMethod("GET");
        // 凭据请求绝不跟随重定向，避免内部密钥与用户令牌被转发到 Location 指向的主机。
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty(INTERNAL_API_KEY_HEADER, request.apiKey());
        connection.setRequestProperty(tokenName, request.satoken());
        connection.setRequestProperty("Cookie", sessionCookieName + "=" + request.sessionCookie());
        // 角色为空时干脆不发这个头：引擎按"非管理员"处理（fail-closed）比发一个空值更明确
        if (StrUtil.isNotBlank(request.userRole())) {
            connection.setRequestProperty(USER_ROLE_HEADER, request.userRole());
        }
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

    /** 引擎读历史端点（T37）：与 /chat 同一内部密钥信道；读历史不需要用户凭据（引擎不碰图库） */
    private static final String ENGINE_MESSAGES_PATH = "/ai/huoshan/conversations";

    /**
     * 按 chatId 拉一场会话的历史消息（T37）。返回引擎响应体原文（BaseResponse JSON），
     * 由前端按 code 判读——代理不重新包装，避免 data 里再嵌一层转义 JSON。
     */
    public String fetchConversationMessages(Long userId, String chatId) {
        String baseUrl = requireConfigured(properties.getEngineBaseUrl(), "图库助手引擎地址未配置");
        String apiKey = requireConfigured(properties.getInternalApiKey(), "图库助手引擎密钥未配置");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(messagesUrl(baseUrl, userId, chatId)).openConnection();
            connection.setRequestMethod("GET");
            // 与 /chat 同口径：绝不跟随重定向，避免内部密钥外泄
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty(INTERNAL_API_KEY_HEADER, apiKey);
            connection.setConnectTimeout(toMillis(properties.getConnectTimeout()));
            connection.setReadTimeout(toMillis(properties.getReadTimeout()));
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                log.warn("AI 助手读历史响应异常, status={}", status);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "助手暂时不可用，请稍后重试");
            }
            return readAll(connection.getInputStream());
        } catch (IOException e) {
            log.warn("AI 助手读历史失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "助手暂时不可用，请稍后重试");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String messagesUrl(String baseUrl, Long userId, String chatId) {
        return StrUtil.removeSuffix(baseUrl, "/") + ENGINE_MESSAGES_PATH
                + "/" + encode(chatId) + "/messages?userId=" + userId;
    }

    /**
     * 把一条确认执行结果事实写进引擎会话记忆（T44/T60）：写操作确认执行后调用，下一轮模型续聊即可读到。
     * <p>
     * 与既有引擎调用同一内部密钥信道（{@link #INTERNAL_API_KEY_HEADER}），不跟随重定向；
     * 归属由引擎按 userId+chatId 派生会话 id。**best-effort**：任何失败只记 warn，不抛——
     * 结果回灌是旁路事实，确认与执行才是主路径，不能因它失败而改变 confirm 的返回。
     * <p>
     * body 是 {@link AiOperationResultFact}：content（模型面说明）+ operationId/type/outcome——
     * 同一会话里多张确认卡片乱序确认时，结果才彼此可区分、失败才可关联。
     */
    public void recordOperationResult(Long userId, String chatId, AiOperationResultFact fact) {
        String baseUrl = requireConfigured(properties.getEngineBaseUrl(), "图库助手引擎地址未配置");
        String apiKey = requireConfigured(properties.getInternalApiKey(), "图库助手引擎密钥未配置");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(operationResultUrl(baseUrl, userId, chatId)).openConnection();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty(INTERNAL_API_KEY_HEADER, apiKey);
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            connection.setConnectTimeout(toMillis(properties.getConnectTimeout()));
            connection.setReadTimeout(toMillis(properties.getReadTimeout()));
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(objectMapper.writeValueAsBytes(fact));
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                log.warn("AI 助手记录操作结果响应异常, status={}", status);
            }
        } catch (IOException e) {
            log.warn("AI 助手记录操作结果失败: {}", e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String operationResultUrl(String baseUrl, Long userId, String chatId) {
        return StrUtil.removeSuffix(baseUrl, "/") + ENGINE_MESSAGES_PATH
                + "/" + encode(chatId) + "/operation-results?userId=" + userId;
    }

    private String readAll(InputStream body) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            return sb.toString();
        }
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
     * 把失败合成一条 {@code error} 事件 + {@code [DONE]} 后收尾（T39）。
     * error 是 F13 协议的既有事件类型（引擎侧失败早已用它），代理侧不再伪装成模型说的话——
     * 前端据此渲染独立错误样式；这里不引入新事件类型、不给引擎加契约。
     */
    private void sendErrorAsError(SseEmitter emitter, String text) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "error");
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
            return "助手未能处理本次请求：" + message;
        }
        return "助手响应异常（错误码 " + status + "）";
    }

    /**
     * 读引擎的错误响应体：401 等错误状态要走 errorStream，200 但非 SSE 的错误体在 inputStream。
     * <p>
     * R7（T27.2）：按字符块读、总长硬上限 {@link #ERROR_BODY_MAX_CHARS}——不用 {@code readLine()}，
     * 否则一条 8000 字符的单行体（没有任何换行）会被整行进内存，"下一行才检查长度"的上限形同虚设；
     * 读到的原文还要过一遍控制字符清洗（见 {@link #withoutControlChars}）。
     */
    private String readErrorBody(HttpURLConnection connection) {
        InputStream stream = connection.getErrorStream();
        if (stream == null) {
            try {
                stream = connection.getInputStream();
            } catch (IOException e) {
                return null;
            }
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder body = new StringBuilder();
            char[] buffer = new char[512];
            while (body.length() < ERROR_BODY_MAX_CHARS) {
                int limit = Math.min(buffer.length, ERROR_BODY_MAX_CHARS - body.length());
                int read = reader.read(buffer, 0, limit);
                if (read < 0) {
                    break;
                }
                body.append(buffer, 0, read);
            }
            return withoutControlChars(body.toString());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 去掉控制字符（含换行、回车、\u000B 这类"会被日志查看器当换行"的字符与 ANSI 转义）。
     * 上游错误体是**不可信输入**：换行会伪造日志行，转义序列会污染终端；而 JSON 的空白只允许出现在
     * token 之间，去掉后仍可解析，所以清洗放在解析之前没有副作用。
     */
    private String withoutControlChars(String body) {
        StringBuilder clean = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!Character.isISOControl(c) && c != '\u2028' && c != '\u2029') {
                clean.append(c);
            }
        }
        return clean.toString();
    }

    /**
     * 从引擎错误 JSON 里取 {@code message}。**不是 JSON 就返回 null**，由调用方改发通用文案：
     * 非 JSON 的错误体多半是网关的 HTML 错误页，把原文塞进回答气泡既读不懂、也把内部细节摊给用户
     * （2026-09-15 复核提升的 P2）；原文只进日志，运维仍拿得到。
     */
    private String extractMessage(String body) {
        if (StrUtil.isBlank(body)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body).path("message");
            if (!node.isMissingNode() && StrUtil.isNotBlank(node.asText())) {
                return node.asText();
            }
            log.warn("引擎的错误体是 JSON 但没有 message 字段，改发通用文案；原文：{}", body);
        } catch (IOException e) {
            log.warn("引擎的错误体不是 JSON（多半是网关页），改发通用文案、原文不发用户；原文：{}", body);
        }
        return null;
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

    /**
     * 一次转发的取消状态与上游连接登记（R4，T27.2）。
     * <p>
     * 取消动作与"登记连接"必须原子配合，两条时序都要覆盖，否则客户端早断时 worker 会白跑一整轮
     * （真实代价：一次完整 LLM 调用链 + 上游负载）：
     * <ul>
     *   <li>取消发生在建连之前 ⇒ {@link #register} 复查取消位，立刻断开且 worker 不进入请求/中继；</li>
     *   <li>取消发生在登记之后 ⇒ {@link #cancel} 直接断开已登记的那条连接。</li>
     * </ul>
     * 断开幂等：重复调用只会断一次（{@code getAndSet} 把引用摘走）。
     */
    static final class Upstream {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();

        /** 客户端断流/出错/超时：先置位再断开已登记的连接 */
        void cancel() {
            cancelled.set(true);
            disconnect();
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        /**
         * worker 建好连接后登记；登记后再查一次取消位——期间已被取消就立即断开并返回 false，
         * worker 不得再进入 {@code getResponseCode()} / {@code relay()}。
         */
        boolean register(HttpURLConnection candidate) {
            connection.set(candidate);
            if (cancelled.get()) {
                disconnect();
                return false;
            }
            return true;
        }

        /** 断开并清空登记（幂等） */
        void disconnect() {
            HttpURLConnection registered = connection.getAndSet(null);
            if (registered != null) {
                registered.disconnect();
            }
        }
    }

    /** 一次转发所需的全部入参（凭据只在请求生命周期内存流转，不落库、不进日志） */
    private record ForwardRequest(String baseUrl, String apiKey, String message, Long userId,
                                  String chatId, String satoken, String sessionCookie, String userRole) {
    }
}
