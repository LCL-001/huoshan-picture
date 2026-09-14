package com.lcl.myaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6 装配单测（docs/plan.md T6）：两个用途各装配一个 OpenAI 协议模型、选项互不串台；
 * 未配置＝该用途不存在（取值报错清晰）；半配置（只填 api-key 或只填 model）直接拒绝装配。
 */
class OpenAiChatModelsTest {

    @Test
    void assemblesAssistantAndVisionIndependently() {
        OpenAiModelProperties properties = new OpenAiModelProperties();
        properties.getAssistant().setBaseUrl("http://localhost:11434/v1");
        properties.getAssistant().setApiKey("assistant-key");
        properties.getAssistant().setModel("qwen3:4b");
        properties.getAssistant().setTemperature(0.4);
        properties.getAssistant().setTimeout(Duration.ofSeconds(60));
        properties.getVision().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.getVision().setApiKey("vision-key");
        properties.getVision().setModel("qwen-vl-plus");
        properties.getVision().setTemperature(0.1);

        OpenAiChatModels models = OpenAiChatModels.from(properties);

        assertThat(models.hasAssistant()).isTrue();
        assertThat(models.hasVision()).isTrue();
        assertThat(optionsOf(models.assistant()).getModel()).isEqualTo("qwen3:4b");
        assertThat(optionsOf(models.assistant()).getTemperature()).isEqualTo(0.4);
        assertThat(optionsOf(models.vision()).getModel()).isEqualTo("qwen-vl-plus");
        assertThat(optionsOf(models.vision()).getTemperature()).isEqualTo(0.1);
    }

    @Test
    void buildsWithoutBaseUrlTimeoutAndTemperature() {
        OpenAiModelProperties properties = new OpenAiModelProperties();
        properties.getAssistant().setApiKey("key");
        properties.getAssistant().setModel("qwen-plus");

        OpenAiChatModels models = OpenAiChatModels.from(properties);

        // base-url 留空走 OpenAI 官方地址、timeout/temperature 留空用默认，均不得抛错
        assertThat(models.hasAssistant()).isTrue();
        assertThat(models.hasVision()).isFalse();
    }

    @Test
    void absentWhenNotConfigured() {
        OpenAiChatModels models = OpenAiChatModels.from(new OpenAiModelProperties());

        assertThat(models.hasAssistant()).isFalse();
        assertThat(models.hasVision()).isFalse();
        assertThatThrownBy(models::assistant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.openai.assistant");
        assertThatThrownBy(models::vision)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.openai.vision");
    }

    @Test
    void rejectsPartiallyConfiguredEntry() {
        OpenAiModelProperties properties = new OpenAiModelProperties();
        properties.getVision().setApiKey("vision-key");

        assertThatThrownBy(() -> OpenAiChatModels.from(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.openai.vision")
                .hasMessageContaining("必须同时提供");
    }

    @Test
    void passesVendorSpecificExtraBodyThrough() {
        OpenAiModelProperties properties = new OpenAiModelProperties();
        properties.getAssistant().setBaseUrl("https://api.deepseek.com");
        properties.getAssistant().setApiKey("deepseek-key");
        properties.getAssistant().setModel("deepseek-flash");
        properties.getAssistant().setExtraBody(Map.of("thinking", Map.of("type", "disabled")));

        OpenAiChatModels models = OpenAiChatModels.from(properties);

        assertThat(optionsOf(models.assistant()).getExtraBody())
                .as("厂商私有开关（如 DeepSeek 的 thinking）必须原样并进请求体，否则思考模式关不掉")
                .containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void extraBodyAbsentWhenNotConfigured() {
        OpenAiModelProperties properties = new OpenAiModelProperties();
        properties.getAssistant().setApiKey("key");
        properties.getAssistant().setModel("deepseek-flash");

        OpenAiChatModels models = OpenAiChatModels.from(properties);

        assertThat(optionsOf(models.assistant()).getExtraBody()).isNullOrEmpty();
    }

    private static OpenAiChatOptions optionsOf(ChatModel chatModel) {
        return (OpenAiChatOptions) chatModel.getDefaultOptions();
    }
}
