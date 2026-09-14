package com.lcl.myaiagent.config;

import cn.hutool.core.util.StrUtil;
import com.lcl.myaiagent.tools.huoshan.HuoshanApiClient;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 图库助手配置（T8 档 1）：图库 backend 地址 + 服务间 API key + 工具调用超时。
 * 密钥只走环境变量 / application-local.yaml，都不入版本库（规则 14）。
 */
@ConfigurationProperties(prefix = "app.huoshan")
public class HuoshanAssistantProperties {

    /** 图库 backend 基地址（含 context-path），如 http://localhost:8123/api */
    private String baseUrl = "http://localhost:8123/api";

    /** 服务间 API key：为空则 headless 端点一律拒绝（fail-closed） */
    private String headlessApiKey;

    /** 图库 API 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 图库 API 读超时：工具调用不能让 ReAct 循环无限等 */
    private Duration readTimeout = Duration.ofSeconds(30);

    /**
     * 按调用者的一组用户名凭据新建图库 API 客户端（per-request，凭据只在请求生命周期）。
     *
     * @param satoken   sa-token 登录态（空间 RBAC 判定用）
     * @param sessionId Spring Session 会话标识（空间列表/详情与空间维度读图的加强校验用）
     */
    public HuoshanApiClient apiClient(String satoken, String sessionId) {
        if (StrUtil.isBlank(baseUrl)) {
            throw new IllegalStateException("图库地址未配置：请设置 app.huoshan.base-url");
        }
        return new HuoshanApiClient(baseUrl, satoken, sessionId, connectTimeout, readTimeout);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getHeadlessApiKey() {
        return headlessApiKey;
    }

    public void setHeadlessApiKey(String headlessApiKey) {
        this.headlessApiKey = headlessApiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
