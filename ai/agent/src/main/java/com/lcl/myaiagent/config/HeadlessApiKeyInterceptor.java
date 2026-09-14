package com.lcl.myaiagent.config;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * headless 端点服务间鉴权（T8 档 1）：只认服务间 API key，不注册登录体系（设计文档 L94）。
 * <p>
 * 两道口径：① 未配置 key 时一律拒绝（fail-closed），避免忘记配置就把内网端点敞开；
 * ② 比对用 {@link MessageDigest#isEqual}，与长度无关的定长比较，不给计时侧信道留口子。
 * </p>
 */
public class HeadlessApiKeyInterceptor implements HandlerInterceptor {

    public static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private static final String UNAUTHORIZED_BODY =
            "{\"code\":40100,\"data\":null,\"message\":\"内部接口未授权：缺少或错误的 " + API_KEY_HEADER + "\"}";

    private final String expectedKey;

    public HeadlessApiKeyInterceptor(String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String provided = request.getHeader(API_KEY_HEADER);
        if (isAuthorized(provided)) {
            return true;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED_BODY);
        return false;
    }

    private boolean isAuthorized(String provided) {
        if (StrUtil.isBlank(expectedKey) || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
