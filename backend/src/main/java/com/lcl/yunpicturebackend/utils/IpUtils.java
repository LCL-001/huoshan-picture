package com.lcl.yunpicturebackend.utils;

import cn.hutool.core.util.StrUtil;

import javax.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 工具
 */
public class IpUtils {

    private IpUtils() {
    }

    /**
     * 获取客户端真实 IP：优先反向代理写入的 X-Real-IP（不可伪造），
     * 其次取 X-Forwarded-For 链最后一跳（最接近服务端，代理追加时不可伪造），否则取 remoteAddr
     */
    public static String getClientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (StrUtil.isNotBlank(realIp)) {
            return realIp.trim();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(xff)) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
