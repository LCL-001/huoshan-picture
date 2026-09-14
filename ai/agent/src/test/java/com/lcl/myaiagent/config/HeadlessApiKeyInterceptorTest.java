package com.lcl.myaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8-lite headless 端点鉴权单测（docs/plan.md T8 档 1）：只有带对 API key 的调用放行；
 * key 没配置时**一律拒绝**（fail-closed，避免"忘记配置 = 内网端口裸奔"）。
 */
class HeadlessApiKeyInterceptorTest {

    private static final String PATH = "/ai/huoshan/chat";

    @Test
    void allowsRequestWithMatchingKey() throws Exception {
        HeadlessApiKeyInterceptor interceptor = new HeadlessApiKeyInterceptor("secret-key");

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request("secret-key"), response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsWrongKey() throws Exception {
        HeadlessApiKeyInterceptor interceptor = new HeadlessApiKeyInterceptor("secret-key");

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request("other-key"), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("40100");
    }

    @Test
    void rejectsMissingKey() throws Exception {
        HeadlessApiKeyInterceptor interceptor = new HeadlessApiKeyInterceptor("secret-key");

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request(null), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void failsClosedWhenKeyNotConfigured() throws Exception {
        HeadlessApiKeyInterceptor interceptor = new HeadlessApiKeyInterceptor("");

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request(""), response, new Object());

        assertThat(allowed)
                .as("未配置服务间 key 时端点必须关闭，而不是对空 key 放行")
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsKeyThatOnlyMatchesAsPrefix() throws Exception {
        HeadlessApiKeyInterceptor interceptor = new HeadlessApiKeyInterceptor("secret-key");

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request("secret-ke"), response, new Object());

        assertThat(allowed).as("定长比较必须区分长度").isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private static MockHttpServletRequest request(String apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        if (apiKey != null) {
            request.addHeader(HeadlessApiKeyInterceptor.API_KEY_HEADER, apiKey);
        }
        return request;
    }
}
