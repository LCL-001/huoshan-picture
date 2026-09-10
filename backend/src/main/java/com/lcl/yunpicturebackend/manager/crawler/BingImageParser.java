package com.lcl.yunpicturebackend.manager.crawler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bing 图片搜索结果解析：把异步接口返回的 HTML 转成原图地址列表。
 * <p>
 * 选择器与字段名全部走配置（app.bing-image.*）。Bing 改版是必然会发生的，
 * 写死在代码里只能等运行时才报错；抽成配置后配合
 * {@code BingImageParserContractTest} 用固定样本钉住契约，结构一变测试先失败。
 * <p>
 * 解析本身不发起网络请求，输入是 HTML 字符串，因此可以脱离网络做契约测试。
 */
@Slf4j
@Component
public class BingImageParser {

    /**
     * 承载搜索结果的容器选择器
     */
    private final String containerSelector;

    /**
     * 单个结果条目的选择器
     */
    private final String itemSelector;

    /**
     * 条目上存放元数据的属性名，值是 JSON 字符串
     */
    private final String metadataAttribute;

    /**
     * 元数据 JSON 里原图地址的字段名
     */
    private final String imageUrlField;

    public BingImageParser(@Value("${app.bing-image.container-selector:.dgControl}") String containerSelector,
                           @Value("${app.bing-image.item-selector:.iusc}") String itemSelector,
                           @Value("${app.bing-image.metadata-attribute:m}") String metadataAttribute,
                           @Value("${app.bing-image.image-url-field:murl}") String imageUrlField) {
        this.containerSelector = containerSelector;
        this.itemSelector = itemSelector;
        this.metadataAttribute = metadataAttribute;
        this.imageUrlField = imageUrlField;
    }

    /**
     * 解析原图地址，最多返回 count 个。
     * <p>
     * 单个条目解析失败只跳过该条，不影响整体；容器缺失则直接抛错——
     * 那说明页面结构已经改到连结果区都找不到了。
     */
    public List<String> parseImageUrls(String html, int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        Document document = Jsoup.parse(html);
        Element container = document.selectFirst(containerSelector);
        if (container == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Bing 页面结构可能已改版：未找到容器 " + containerSelector);
        }
        Elements items = container.select(itemSelector);
        if (items.isEmpty()) {
            // 不抛错：冷门查询词本来就可能没有结果。但要把"选择器可能已失效"打到日志里，
            // 否则线上只会表现为"抓取到 0 张图"，看不出原因
            log.warn("[bing-parse] 未匹配到任何条目，Bing 页面结构可能已改版, container={}, item={}",
                    containerSelector, itemSelector);
            return Collections.emptyList();
        }
        List<String> imageUrls = new ArrayList<>(Math.min(count, items.size()));
        for (Element item : items) {
            if (imageUrls.size() >= count) {
                break;
            }
            String metadata = item.attr(metadataAttribute);
            if (StrUtil.isBlank(metadata)) {
                continue;
            }
            try {
                String fileUrl = JSONUtil.parseObj(metadata).getStr(imageUrlField);
                if (StrUtil.isBlank(fileUrl)) {
                    continue;
                }
                // 去掉原图地址上的查询参数（尺寸/裁剪参数会让同一张图产生多个 URL）
                int questionMarkIndex = fileUrl.indexOf("?");
                if (questionMarkIndex > -1) {
                    fileUrl = fileUrl.substring(0, questionMarkIndex);
                }
                imageUrls.add(fileUrl);
            } catch (Exception e) {
                log.error("[bing-parse] 解析图片数据失败, attribute={}", metadataAttribute, e);
            }
        }
        return imageUrls;
    }
}
