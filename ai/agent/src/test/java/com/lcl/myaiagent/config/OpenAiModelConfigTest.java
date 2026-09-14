package com.lcl.myaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6 配置契约单测（docs/plan.md T6）：kebab-case 绑定到两套配置项；持有器是容器里唯一的模型相关 Bean
 * （不新增 ChatModel Bean，避免与 profile 默认模型 / ChatClient.Builder 自动装配按类型冲突）；
 * 提交进库的 application.yaml 必须显式关掉 OpenAI 的其余模型类型自动装配。
 */
class OpenAiModelConfigTest {

    @Test
    void bindsKebabCaseEntryAndDuration() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "app.ai.openai.assistant.base-url", "http://localhost:11434/v1",
                "app.ai.openai.assistant.api-key", "key",
                "app.ai.openai.assistant.model", "qwen3:4b",
                "app.ai.openai.assistant.temperature", "0.4",
                "app.ai.openai.assistant.timeout", "90s",
                "app.ai.openai.vision.model", "qwen-vl-plus"));

        OpenAiModelProperties properties = new Binder(source)
                .bind("app.ai.openai", OpenAiModelProperties.class)
                .orElseThrow(() -> new AssertionError("app.ai.openai 未绑定成功"));

        assertThat(properties.getAssistant().getBaseUrl()).isEqualTo("http://localhost:11434/v1");
        assertThat(properties.getAssistant().getApiKey()).isEqualTo("key");
        assertThat(properties.getAssistant().getModel()).isEqualTo("qwen3:4b");
        assertThat(properties.getAssistant().getTemperature()).isEqualTo(0.4);
        assertThat(properties.getAssistant().getTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(properties.getAssistant().isConfigured()).isTrue();
        // vision 只配了 model：绑定得上，但视为未配置
        assertThat(properties.getVision().getModel()).isEqualTo("qwen-vl-plus");
        assertThat(properties.getVision().isConfigured()).isFalse();
    }

    @Test
    void registersHolderWithoutAddingChatModelBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(OpenAiModelConfig.class)
                .withPropertyValues(
                        "app.ai.openai.assistant.base-url=http://localhost:11434/v1",
                        "app.ai.openai.assistant.api-key=key",
                        "app.ai.openai.assistant.model=qwen3:4b")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenAiChatModels.class);
                    assertThat(context.getBean(OpenAiChatModels.class).hasAssistant()).isTrue();
                    // 关键约束：装配结果不进容器做 ChatModel Bean（否则与默认 ChatModel / ChatClient.Builder 抢类型）
                    assertThat(context).doesNotHaveBean(ChatModel.class);
                });
    }

    @Test
    void committedConfigDisablesOpenAiAutoConfiguration() throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))
                .forEach(propertySources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));

        // spring-ai-starter-model-openai 的六条模型自动装配都是 matchIfMissing = true：属性缺省即启用。
        // chat / embedding 已由既有 yaml（及 ollama profile）定为 dashscope / ollama，
        // 其余四项由 T6 显式关掉——这里把"必须显式声明且不得为 openai"钉住。
        for (String modelType : List.of("chat", "embedding", "image", "audio.speech", "audio.transcription", "moderation")) {
            String property = "spring.ai.model." + modelType;
            BindResult<String> value = binder.bind(property, String.class);
            assertThat(value.isBound())
                    .as("%s 必须显式声明（缺省即 matchIfMissing=true 会启用 OpenAI 自动装配）", property)
                    .isTrue();
            assertThat(value.orElse(null))
                    .as("%s 不得为 openai（会启用 OpenAI 自动装配）", property)
                    .isNotEqualTo("openai");
        }
    }
}
