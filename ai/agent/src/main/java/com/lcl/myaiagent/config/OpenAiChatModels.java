package com.lcl.myaiagent.config;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.CompositeRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * OpenAI 协议模型组（T6）：主脑与 visionTagger 视觉模型的装配结果持有器。
 * <p>
 * 刻意不注册成 ChatModel Bean：容器里已有 profile 决定的默认 ChatModel（ollama / dashscope），
 * 而 Spring AI 的 ChatClient.Builder 自动装配按类型取单个 ChatModel，再放两个 ChatModel 进去
 * 会让该自动装配与既有按类型注入同时变成不唯一。需要哪个模型，由调用方按会话类型显式取（T7/T8）。
 * </p>
 * <p>
 * T18 起两个模型都挂**有界重试模板**（见 {@link #boundedRetryTemplate()}）：默认模板的
 * 10 次 + 指数退避到 180s 与这条链路的 300s 上限不相容。
 * </p>
 */
@Slf4j
public final class OpenAiChatModels {

    /** 重试总预算：从首次尝试起算的墙钟上界，必须明显小于 SSE emitter 的 300s（T18） */
    static final Duration RETRY_BUDGET = Duration.ofSeconds(45);

    /** 尝试次数上限（含首次）：4 次 = 1 次 + 3 次重试（T18） */
    static final int RETRY_MAX_ATTEMPTS = 4;

    /** 退避起点：秒失败的形态下 0.5s / 1s / 2s 三级，总等待不到 4s（T18） */
    static final Duration RETRY_INITIAL_BACKOFF = Duration.ofMillis(500);

    /** 退避封顶（默认模板封顶 180s，对交互式请求等于挂死）（T18） */
    static final Duration RETRY_MAX_BACKOFF = Duration.ofSeconds(4);

    private final ChatModel assistant;

    private final ChatModel vision;

    private OpenAiChatModels(ChatModel assistant, ChatModel vision) {
        this.assistant = assistant;
        this.vision = vision;
    }

    /**
     * 按配置装配两个模型；未配置的用途为"不存在"，不影响普通会话。
     *
     * @param properties 模型配置项
     * @return 模型组
     */
    public static OpenAiChatModels from(OpenAiModelProperties properties) {
        return new OpenAiChatModels(
                build("app.ai.openai.assistant", properties.getAssistant()),
                build("app.ai.openai.vision", properties.getVision()));
    }

    /**
     * 图库助手主脑模型。
     */
    public ChatModel assistant() {
        if (assistant == null) {
            throw new IllegalStateException(
                    "图库助手主脑模型未配置：请同时设置 app.ai.openai.assistant.api-key 与 app.ai.openai.assistant.model");
        }
        return assistant;
    }

    /**
     * visionTagger 看图模型。
     */
    public ChatModel vision() {
        if (vision == null) {
            throw new IllegalStateException(
                    "visionTagger 看图模型未配置：请同时设置 app.ai.openai.vision.api-key 与 app.ai.openai.vision.model");
        }
        return vision;
    }

    public boolean hasAssistant() {
        return assistant != null;
    }

    public boolean hasVision() {
        return vision != null;
    }

    private static ChatModel build(String prefix, OpenAiModelProperties.ModelEntry entry) {
        if (entry == null || !entry.isConfigured()) {
            if (entry != null && entry.isPartiallyConfigured()) {
                throw new IllegalStateException(prefix + " 配置不完整：api-key 与 model 必须同时提供");
            }
            return null;
        }
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .apiKey(entry.getApiKey())
                .restClientBuilder(restClientBuilder(entry.getTimeout()))
                .webClientBuilder(webClientBuilder(entry.getTimeout()));
        if (StrUtil.isNotBlank(entry.getBaseUrl())) {
            apiBuilder.baseUrl(entry.getBaseUrl());
        }
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder().model(entry.getModel());
        if (entry.getTemperature() != null) {
            optionsBuilder.temperature(entry.getTemperature());
        }
        if (entry.getExtraBody() != null && !entry.getExtraBody().isEmpty()) {
            optionsBuilder.extraBody(entry.getExtraBody());
        }
        // 启动即把生效的接入点打出来：多厂商配置下最容易错的就是 base-url（多写/少写 /v1）
        // extraBody 只打键名不打值（它承载厂商私有开关，键名足够定位配置来源）
        log.info("OpenAI 协议模型就绪：config={}, baseUrl={}, model={}, temperature={}, timeout={}, extraBodyKeys={}",
                prefix, StrUtil.isBlank(entry.getBaseUrl()) ? "(厂商默认)" : entry.getBaseUrl(),
                entry.getModel(), entry.getTemperature(), entry.getTimeout(),
                entry.getExtraBody() == null ? "[]" : entry.getExtraBody().keySet());
        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(optionsBuilder.build())
                // T18：不给模板就落到 RetryUtils.DEFAULT_RETRY_TEMPLATE（10 次 + 退避到 180s ≈ 19 分钟）
                .retryTemplate(boundedRetryTemplate())
                .build();
    }

    /**
     * 有界重试模板（T18）：替代 Spring AI 的 {@code RetryUtils.DEFAULT_RETRY_TEMPLATE}。
     * <p>
     * 默认模板是 {@code maxAttempts(10)} + 指数退避 2s→180s，9 段退避合计约 <b>19 分钟</b>；
     * 而这条链路的上限是 SSE emitter 的 300s，于是"provider 不可达"这类失败会让用户在 300s 时被
     * **裸断连**（T8-d 实测：error 事件根本没机会发出）。这里给两把尺子，取"都允许才重试"：
     * <ul>
     * <li><b>尝试上限 4 次</b>——对付**秒失败**（连接被拒、瞬时 5xx/限流）：退避 0.5/1/2s，总等待不到 4s；</li>
     * <li><b>总预算 45s</b>——对付**慢失败**（单次就耗掉自己的超时）：首次尝试一超预算就不再重试，
     * 整次调用墙钟 ≈ 单次超时（主脑 {@code assistant.timeout} 60s / 看图 45s），而不是 10 倍。</li>
     * </ul>
     * 刻意不用现成的 {@code RetryUtils.SHORT_RETRY_TEMPLATE}：它只是把退避换成固定 100ms，
     * <b>仍是 10 次尝试</b>，对"每次都要 60s 才超时"的形态等于 10 分钟，等于没修。
     * </p>
     */
    static RetryTemplate boundedRetryTemplate() {
        return boundedRetryTemplate(RETRY_BUDGET, RETRY_MAX_ATTEMPTS, RETRY_INITIAL_BACKOFF, RETRY_MAX_BACKOFF);
    }

    /** 包级可见（测试用）：注入更小的预算/退避，既能跑得快，也能钉住"预算真的会把重试截断" */
    static RetryTemplate boundedRetryTemplate(Duration budget, int maxAttempts,
                                              Duration initialBackoff, Duration maxBackoff) {
        SimpleRetryPolicy attempts = new SimpleRetryPolicy(maxAttempts, Map.of(
                TransientAiException.class, true,
                ResourceAccessException.class, true));
        TimeoutRetryPolicy budgetPolicy = new TimeoutRetryPolicy(budget.toMillis());
        CompositeRetryPolicy policy = new CompositeRetryPolicy();
        policy.setOptimistic(false);
        policy.setPolicies(new RetryPolicy[] {attempts, budgetPolicy});

        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(initialBackoff.toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(maxBackoff.toMillis());

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(policy);
        template.setBackOffPolicy(backOff);
        return template;
    }

    /**
     * 阻塞调用必须有超时，否则一次卡死的请求就能把 ReAct 循环拖住（Spring AI 默认 RestClient 不设读取超时）。
     */
    private static RestClient.Builder restClientBuilder(Duration timeout) {
        if (timeout == null) {
            return RestClient.builder();
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * 流式调用同样必须有超时：{@code OpenAiApi.Builder} 的流式请求走 WebClient，
     * 而它的无参构造兜底是 {@code WebClient.builder()}（没接超时），只配 restClient 只保护了阻塞路径——
     * 上游一次卡死就会永久挂住 SSE 连接。这里补两件事：connector 的连接/响应头超时
     * （见 {@link #streamingConnector}）与响应体流的空闲超时（见 {@link #streamIdleTimeout}）。
     * 0 与负数视作"不限制"，与阻塞路径 SimpleClientHttpRequestFactory 的 0=无限一致。
     */
    private static WebClient.Builder webClientBuilder(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return WebClient.builder();
        }
        return WebClient.builder()
                .clientConnector(streamingConnector(timeout))
                .filter(streamIdleTimeout(timeout));
    }

    /**
     * 响应体流的空闲超时：{@code timeout} 内收不到任何信号（字节或结束）就中断这次流式请求。
     * <p>
     * 只给 connector 接超时不够——它的读超时等价于 JDK 的请求超时，只覆盖到响应头到达，
     * 服务端吐了几段之后静默不会触发（review 实测 20s 仍挂）。这里按时长逐请求计时，
     * 所以工具调用轮次之间等待下一轮响应的间隔不会被误伤。
     * </p>
     */
    private static ExchangeFilterFunction streamIdleTimeout(Duration timeout) {
        return (request, next) -> next.exchange(request)
                .map(response -> response.mutate()
                        .body(body -> body.timeout(timeout))
                        .build());
    }

    /**
     * 流式路径的 HTTP connector：显式用 JDK 客户端（本项目 classpath 无 reactor-netty/jetty/HC5，
     * WebClient 的自动探测本来就落到它），以便同时设上连接超时与响应头超时——
     * JDK 客户端这两项默认都是不限制。
     * <p>
     * 包级可见以便单测断言两个超时确实接到了客户端上。
     * </p>
     */
    static ClientHttpConnector streamingConnector(Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpConnector connector = new JdkClientHttpConnector(httpClient);
        connector.setReadTimeout(timeout);
        return connector;
    }
}
