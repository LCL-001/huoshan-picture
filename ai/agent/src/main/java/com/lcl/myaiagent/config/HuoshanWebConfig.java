package com.lcl.myaiagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * headless 端点装配（T8 档 1）：把服务间 API key 拦截器挂到 /ai/huoshan/** 上。
 * 只拦这条路径——引擎自身前端用的 /ai/manus/** 与其余接口行为零改动。
 */
@Configuration
@EnableConfigurationProperties(HuoshanAssistantProperties.class)
public class HuoshanWebConfig implements WebMvcConfigurer {

    private final HuoshanAssistantProperties properties;

    public HuoshanWebConfig(HuoshanAssistantProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HeadlessApiKeyInterceptor(properties.getHeadlessApiKey()))
                .addPathPatterns("/ai/huoshan/**");
    }
}
