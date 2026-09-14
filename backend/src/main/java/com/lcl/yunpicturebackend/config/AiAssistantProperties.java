package com.lcl.yunpicturebackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 助手代理配置（T10）。
 * <p>
 * 服务间密钥必须与引擎侧 {@code app.huoshan.headless-api-key} 一致；
 * 密钥只走环境变量 / application-local.yaml，不入库。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.assistant")
public class AiAssistantProperties {

    /** 引擎（ai/agent）基地址，含 context-path，例：http://localhost:8124/api */
    private String engineBaseUrl = "http://localhost:8124/api";

    /** 服务间 API key；空白时代理端点一律拒绝（fail-closed，避免"忘记配置 = 内网引擎裸奔"） */
    private String internalApiKey;

    /** 建连超时 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 读取超时（含流空闲）：必须大于引擎 SseEmitter 的 300s，否则正常长对话会被代理提前掐断 */
    private Duration readTimeout = Duration.ofMinutes(6);
}
