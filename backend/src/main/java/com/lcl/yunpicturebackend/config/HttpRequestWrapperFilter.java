package com.lcl.yunpicturebackend.config;

import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * 请求包装过滤器
 *
 * @author pine
 */
@Order(1)
@Component
public class HttpRequestWrapperFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest servletRequest = (HttpServletRequest) request;
            String contentType = servletRequest.getHeader(Header.CONTENT_TYPE.getValue());
            // 判断条件必须与 StpInterfaceImpl#getAuthContextByRequest 保持一致：
            // 这里不包装而那边走 getBody，会消费未包装请求的输入流，破坏后续 @RequestBody 解析。
            // 忽略大小写的 startsWith 兼容 "application/json;charset=UTF-8" 等带参数的媒体类型，
            // 精确等于会让这类 JSON 请求不被包装、权限嗅探读不到 body
            if (contentType != null && contentType.toLowerCase().startsWith(ContentType.JSON.getValue())) {
                // 可以再细粒度一些，只有需要进行空间权限校验的接口才需要包一层
                chain.doFilter(new RequestWrapper(servletRequest), response);
            } else {
                chain.doFilter(request, response);
            }
        }
    }

}
