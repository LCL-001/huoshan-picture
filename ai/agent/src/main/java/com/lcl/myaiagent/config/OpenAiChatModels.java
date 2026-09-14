package com.lcl.myaiagent.config;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * OpenAI 协议模型组（T6）：主脑与 visionTagger 视觉模型的装配结果持有器。
 * <p>
 * 刻意不注册成 ChatModel Bean：容器里已有 profile 决定的默认 ChatModel（ollama / dashscope），
 * 而 Spring AI 的 ChatClient.Builder 自动装配按类型取单个 ChatModel，再放两个 ChatModel 进去
 * 会让该自动装配与既有按类型注入同时变成不唯一。需要哪个模型，由调用方按会话类型显式取（T7/T8）。
 * </p>
 */
@Slf4j
public final class OpenAiChatModels {

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
                .restClientBuilder(restClientBuilder(entry.getTimeout()));
        if (StrUtil.isNotBlank(entry.getBaseUrl())) {
            apiBuilder.baseUrl(entry.getBaseUrl());
        }
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder().model(entry.getModel());
        if (entry.getTemperature() != null) {
            optionsBuilder.temperature(entry.getTemperature());
        }
        // 启动即把生效的接入点打出来：多厂商配置下最容易错的就是 base-url（多写/少写 /v1）
        log.info("OpenAI 协议模型就绪：config={}, baseUrl={}, model={}, temperature={}, timeout={}",
                prefix, StrUtil.isBlank(entry.getBaseUrl()) ? "(厂商默认)" : entry.getBaseUrl(),
                entry.getModel(), entry.getTemperature(), entry.getTimeout());
        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(optionsBuilder.build())
                .build();
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
}
