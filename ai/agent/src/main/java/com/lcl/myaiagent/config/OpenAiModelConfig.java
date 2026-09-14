package com.lcl.myaiagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI 协议模型装配（T6）：绑定 app.ai.openai.* 并装配成 OpenAiChatModels 持有器。
 */
@Configuration
@EnableConfigurationProperties(OpenAiModelProperties.class)
public class OpenAiModelConfig {

    @Bean
    public OpenAiChatModels openAiChatModels(OpenAiModelProperties properties) {
        return OpenAiChatModels.from(properties);
    }
}
