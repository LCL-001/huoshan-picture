package com.lcl.myaiagent.chatmemory;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.lcl.myaiagent.config.OpenAiChatModels;
import com.lcl.myaiagent.config.OpenAiModelProperties;
import com.lcl.myaiagent.model.po.ChatMessage;
import com.lcl.myaiagent.model.po.ChatSummary;
import com.lcl.myaiagent.repository.ChatMessageRepository;
import com.lcl.myaiagent.repository.ChatSummaryRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 摘要走哪个模型（2026-09-16）。
 * <p>
 * 口径：摘要压缩走**图库助手主脑**（OpenAI 协议，prod 上是 MiMo），不再用容器里那个由
 * {@code spring.ai.model.chat} 决定的默认 ChatModel——否则"摘要能不能用"就绑在 DashScope 的 key 上
 * （假 key 能启动、长对话摘要失败）。主脑未配置时不做二次回退，直接降级为硬裁剪（有日志）。
 * </p>
 * <p>
 * 不用 Mockito 桩主脑（{@link OpenAiChatModels} 是 final 类，且真正要证的正是"请求真的发到了主脑的
 * base-url"）：这里起一个 JDK 自带的桩 HTTP 服务当 MiMo，把打进来的请求记下来。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("摘要模型：走主脑，配不上就降级")
class FlowWindowBasedChatMemorySummaryModelTest {

    private static final String CHAT_ID = "summary-model-chat";

    /** 摘要 prompt 里的固定片段：用它证明打到桩服务上的那次请求确实是摘要请求 */
    private static final String SUMMARY_PROMPT_MARKER = "压缩成一段简洁的摘要";

    private static final String STUB_SUMMARY = "桩返回的摘要";

    /** 每条 = 一次打进桩服务的请求，格式 `路径\n请求体` */
    private static final List<String> REQUESTS = Collections.synchronizedList(new ArrayList<>());

    private static HttpServer stubServer;

    private static String baseUrl;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatSummaryRepository chatSummaryRepository;

    @BeforeAll
    static void startStubServer() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/", FlowWindowBasedChatMemorySummaryModelTest::handle);
        stubServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-mimo-server");
            thread.setDaemon(true);
            return thread;
        }));
        stubServer.start();
        baseUrl = "http://127.0.0.1:" + stubServer.getAddress().getPort();
    }

    @AfterAll
    static void stopStubServer() {
        stubServer.stop(0);
    }

    @BeforeEach
    void resetRequests() {
        // 打点是静态的（桩服务是类级资源），不清就会串到下一个用例
        REQUESTS.clear();
    }

    @Test
    @DisplayName("主脑已配置：摘要请求打到主脑 base-url，并正常落库")
    void summaryGoesToAssistantModel() {
        FlowWindowBasedChatMemory memory = memoryWith(mimoModels());

        ChatSummary saved = compressAndCaptureSummary(memory);

        assertThat(saved.getSummary()).isEqualTo(STUB_SUMMARY);
        assertThat(REQUESTS)
                .as("摘要应恰好发一次请求到主脑端点")
                .hasSize(1);
        assertThat(REQUESTS.get(0))
                .as("请求路径由 Spring AI 按 OpenAI 协议拼出")
                .startsWith("/v1/chat/completions");
        assertThat(REQUESTS.get(0))
                .as("打到主脑上的必须真的是摘要请求")
                .contains(SUMMARY_PROMPT_MARKER);
    }

    @Test
    @DisplayName("主脑未配置：零请求、不落摘要，降级为硬裁剪（压缩失败不影响可用性）")
    void unconfiguredAssistantDegradesToTrim() {
        FlowWindowBasedChatMemory memory = memoryWith(OpenAiChatModels.from(new OpenAiModelProperties()));

        List<Message> result = memory.get(CHAT_ID);

        assertThat(REQUESTS)
                .as("主脑没配，不该有任何请求发出去")
                .isEmpty();
        verify(chatSummaryRepository, never()).saveOrUpdate(any());
        assertThat(result).as("降级后仍要能返回可用历史").isNotEmpty();
    }

    // ---------- 装配与桩 ----------

    private FlowWindowBasedChatMemory memoryWith(OpenAiChatModels models) {
        mockMessageQuery(overBudgetHistory());
        mockSummaryQuery();
        return new FlowWindowBasedChatMemory(chatMessageRepository, chatSummaryRepository, models);
    }

    /** 主脑 = 桩服务（MiMo 的协议根不带尾部 /v1，与 T6 口径一致） */
    private static OpenAiChatModels mimoModels() {
        OpenAiModelProperties properties = new OpenAiModelProperties();
        properties.getAssistant().setBaseUrl(baseUrl);
        properties.getAssistant().setApiKey("test-key");
        properties.getAssistant().setModel("mimo-v2.5");
        properties.getAssistant().setTimeout(Duration.ofSeconds(5));
        return OpenAiChatModels.from(properties);
    }

    /** 1 条 system(150 token) + 12 条普通消息(每条 450 token)：全量超预算，且老段够长、必走首次压缩 */
    private static List<ChatMessage> overBudgetHistory() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(msg(100, MessageType.SYSTEM, 100));
        for (long id = 101; id <= 112; id++) {
            history.add(msg(id, MessageType.USER, 300));
        }
        return history;
    }

    private static ChatMessage msg(long id, MessageType type, int nChars) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setConversationId(CHAT_ID);
        m.setMessageType(type);
        m.setContent("x".repeat(nChars));
        return m;
    }

    @SuppressWarnings("unchecked")
    private void mockMessageQuery(List<ChatMessage> history) {
        LambdaQueryChainWrapper<ChatMessage> q = mock(LambdaQueryChainWrapper.class);
        doReturn(q).when(q).eq(any(SFunction.class), any());
        doReturn(q).when(q).orderByAsc(any(SFunction.class));
        doReturn(history).when(q).list();
        doReturn(q).when(chatMessageRepository).lambdaQuery();
    }

    /** 库里没有旧摘要 → 必走"首次压缩"，也就是必然要调一次模型 */
    @SuppressWarnings("unchecked")
    private void mockSummaryQuery() {
        LambdaQueryChainWrapper<ChatSummary> q = mock(LambdaQueryChainWrapper.class);
        doReturn(q).when(q).eq(any(SFunction.class), any());
        doReturn(null).when(q).one();
        doReturn(q).when(chatSummaryRepository).lambdaQuery();
        when(chatSummaryRepository.saveOrUpdate(any(ChatSummary.class))).thenReturn(true);
    }

    private ChatSummary compressAndCaptureSummary(FlowWindowBasedChatMemory memory) {
        memory.get(CHAT_ID);
        ArgumentCaptor<ChatSummary> captor = ArgumentCaptor.forClass(ChatSummary.class);
        verify(chatSummaryRepository).saveOrUpdate(captor.capture());
        return captor.getValue();
    }

    private static void handle(com.sun.net.httpserver.HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            REQUESTS.add(path + "\n" + body);

            byte[] json = ("""
                    {"id":"chatcmpl-stub","object":"chat.completion","created":1,"model":"stub-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                    .formatted(STUB_SUMMARY)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(json);
            }
        } catch (IOException ignored) {
            // 客户端主动断开是测试自己的事，不是服务端错误
        } finally {
            exchange.close();
        }
    }
}
