package com.lcl.yunpicturebackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许跨域的来源，逗号分隔（支持 * 通配模式）。
     * 默认配置面向本地开发（见 application.yaml）；生产环境在 application-prod.yaml
     * 中覆盖为线上域名，不含 localhost。
     */
    @Value("#{'${app.cors.allowed-origins}'.split(',')}")
    private List<String> allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 覆盖所有请求
        registry.addMapping("/**")
                // 允许发送 Cookie
                .allowCredentials(true)
                // 放行哪些域名（必须用 patterns，否则 * 会和 allowCredentials 冲突）
                .allowedOriginPatterns(allowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*");
    }
}
