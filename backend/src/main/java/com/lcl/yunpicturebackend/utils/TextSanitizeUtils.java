package com.lcl.yunpicturebackend.utils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import java.util.List;
import java.util.stream.Collectors;

/**
 * UGC 文本清洗工具
 * 入库前剥离全部 HTML 标签，防止存储型 XSS（前端均为插值转义渲染，服务端不存储任何标记）
 */
public class TextSanitizeUtils {

    private TextSanitizeUtils() {
    }

    /**
     * 剥离文本中的全部 HTML 标签；残余的特殊字符以实体形式转义存储，
     * 保证存储值中不会出现可构成标签的原始 &lt; &gt;。null/空白原样返回。
     */
    public static String stripHtml(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        return Jsoup.clean(text, Safelist.none());
    }

    /**
     * 批量剥离列表内每个元素的 HTML 标签
     */
    public static List<String> stripHtmlList(List<String> texts) {
        if (CollUtil.isEmpty(texts)) {
            return texts;
        }
        return texts.stream().map(TextSanitizeUtils::stripHtml).collect(Collectors.toList());
    }
}
