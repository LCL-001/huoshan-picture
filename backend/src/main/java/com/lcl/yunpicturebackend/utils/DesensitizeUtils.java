package com.lcl.yunpicturebackend.utils;

/**
 * 脱敏工具
 */
public class DesensitizeUtils {

    private DesensitizeUtils() {
    }

    /**
     * 邮箱脱敏：保留首字符与域名，如 abcd@qq.com -> a***@qq.com
     */
    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
