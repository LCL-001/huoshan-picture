package com.lcl.myaiagent.config;

import cn.hutool.core.util.StrUtil;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * OpenAI 协议模型配置（T6）：图库助手主脑与 visionTagger 视觉模型各一套独立配置项，
 * 厂商不绑死——DashScope 兼容模式 / DeepSeek / Moonshot / GLM / SiliconFlow / vLLM /
 * 本地 Ollama 的 /v1 兼容端点都可以，换厂商只改配置。
 * <p>
 * 密钥走环境变量或 application-local.yaml 注入，两者都不入版本库。
 * </p>
 */
@ConfigurationProperties(prefix = "app.ai.openai")
public class OpenAiModelProperties {

    /** 图库助手主脑（文本模型） */
    private ModelEntry assistant = new ModelEntry();

    /** visionTagger 看图模型（多模态，接收 image_url 输入） */
    private ModelEntry vision = new ModelEntry();

    public ModelEntry getAssistant() {
        return assistant;
    }

    public void setAssistant(ModelEntry assistant) {
        this.assistant = assistant;
    }

    public ModelEntry getVision() {
        return vision;
    }

    public void setVision(ModelEntry vision) {
        this.vision = vision;
    }

    /**
     * 单个模型的配置项：api-key 与 model 都给全才算配置完成。
     */
    public static class ModelEntry {

        /** OpenAI 协议基地址（留空走 OpenAI 官方地址） */
        private String baseUrl;

        /** API Key */
        private String apiKey;

        /** 模型名 */
        private String model;

        /** 采样温度（留空用服务端默认） */
        private Double temperature;

        /** 单次请求超时，连接与读取共用（留空不限制） */
        private Duration timeout;

        /**
         * 配置是否完整可用。
         */
        public boolean isConfigured() {
            return StrUtil.isNotBlank(apiKey) && StrUtil.isNotBlank(model);
        }

        /**
         * 是否处于半配置状态（api-key / model 只填了一个）。
         * 属配置错误，装配时直接拒绝，避免"配了却不生效"的静默失败。
         */
        public boolean isPartiallyConfigured() {
            return StrUtil.isNotBlank(apiKey) != StrUtil.isNotBlank(model);
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
