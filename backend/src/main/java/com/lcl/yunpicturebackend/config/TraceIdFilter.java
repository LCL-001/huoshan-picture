package com.lcl.yunpicturebackend.config;

import com.lcl.yunpicturebackend.manager.observability.TraceContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 全链路追踪入口：为每个请求解析或生成 traceId，写入 MDC 并回写响应头。
 * <p>
 * 必须排在其它过滤器之前（比 HttpRequestWrapperFilter 的 @Order(1) 更早），
 * 这样整条链路——包括后续过滤器和全局异常处理器的日志——都能带上 traceId。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceContext.resolve(request.getHeader(TraceContext.TRACE_ID_HEADER));
        TraceContext.set(traceId);
        // 回写响应头，前端和压测报告可以直接拿到链路标识
        response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat 线程会复用，不清理会污染后续请求的日志
            TraceContext.clear();
        }
    }
}
