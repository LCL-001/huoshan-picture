package com.lcl.myaiagent.utils;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;

/**
 * 网络工具类
 */
public class NetUtils {

    /**
     * 获取客户端真实 IP 地址
     */
    public static String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
            if ("127.0.0.1".equals(ip)) {
                try {
                    InetAddress inet = InetAddress.getLocalHost();
                    ip = inet.getHostAddress();
                } catch (Exception ignored) {
                }
            }
        }
        // 多个代理时取第一个 IP
        if (ip != null && ip.length() > 15 && ip.indexOf(',') > 0) {
            ip = ip.substring(0, ip.indexOf(','));
        }
        return ip != null ? ip : "127.0.0.1";
    }
}
