package com.lcl.yunpicturebackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求包装过滤器的媒体类型判断：JSON（含带参数写法）必须被包装，body 才能同时供权限嗅探与 @RequestBody 读取；
 * 非 JSON 一律原样放行。该判断必须与 StpInterfaceImpl#getAuthContextByRequest 保持一致。
 * 历史上精确匹配 "application/json" 曾让带 charset 的 JSON 请求绕过嗅探、令授权上下文为空（安全缺陷，见 docs/plan.md T3.1）。
 */
class HttpRequestWrapperFilterTest {

    private final HttpRequestWrapperFilter filter = new HttpRequestWrapperFilter();

    @Test
    void plainJsonShouldBeWrapped() throws Exception {
        assertTrue(isWrapped("application/json"));
    }

    @Test
    void jsonWithCharsetShouldBeWrapped() throws Exception {
        assertTrue(isWrapped("application/json;charset=UTF-8"));
        assertTrue(isWrapped("Application/JSON;charset=UTF-8"));
    }

    @Test
    void nonJsonShouldNotBeWrapped() throws Exception {
        assertFalse(isWrapped("multipart/form-data;boundary=xxx"));
        assertFalse(isWrapped("text/plain"));
    }

    @Test
    void missingContentTypeShouldNotBeWrapped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertSame(request, chain.getRequest());
    }

    private boolean isWrapped(String contentType) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType(contentType);
        request.addHeader("Content-Type", contentType);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest() instanceof RequestWrapper;
    }
}
