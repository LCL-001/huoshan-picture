package com.lcl.yunpicturebackend.manager.crawler;

import com.lcl.yunpicturebackend.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bing 抓取的契约测试：用固定 HTML 样本钉住"容器 + 条目 + murl"这组结构。
 * <p>
 * 样本见 src/test/resources/bing/image-search-sample.html（取自真实响应并裁剪）。
 * Bing 改版时这份样本与断言会先失败，而不是等线上抓取到 0 张图才发现。
 */
class BingImageParserContractTest {

    /**
     * 与 application.yaml 中 app.bing-image.* 的默认值一致
     */
    private final BingImageParser parser = new BingImageParser(".dgControl", ".iusc", "m", "murl");

    private final String sampleHtml = readSample();

    @Test
    void shouldExtractImageUrlsInDocumentOrderAndStripQuery() {
        List<String> urls = parser.parseImageUrls(sampleHtml, 10);

        // 5 个条目里 murl 为空的那个被跳过，其余按文档顺序返回；带 query 的要去掉 query
        assertEquals(List.of(
                "https://i.gei6.com/p_img/danci/cat-1.jpg",
                "https://betterwithcats.net/wp-content/uploads/2022/08/cat-is-laying-on-a-old-wooden-table.jpg",
                "https://www.thesprucepets.com/thmb/LT1TK3rcLHo15M66c7kIH8lIm44=/2782x1855/filters:fill(auto,1)/calico-cats-profile-554694-hero.jpg",
                "https://example.com/with-query.jpg"
        ), urls);
    }

    @Test
    void shouldRespectCountLimit() {
        assertEquals(2, parser.parseImageUrls(sampleHtml, 2).size());
        assertTrue(parser.parseImageUrls(sampleHtml, 0).isEmpty());
    }

    /**
     * 连结果容器都找不到，说明结构改得比较彻底，必须快速失败而不是静默返回 0 张
     */
    @Test
    void shouldFailFastWhenContainerMissing() {
        String html = "<html><body><div class=\"other\">改版后的结构</div></body></html>";

        BusinessException exception = assertThrows(BusinessException.class,
                () -> parser.parseImageUrls(html, 10));
        assertTrue(exception.getMessage().contains(".dgControl"), "报错要带上失效的选择器，便于定位：" + exception.getMessage());
    }

    /**
     * 容器在、条目选择器匹配不到：属于"选择器可能失效"，返回空列表但要留日志（见 BingImageParser）
     */
    @Test
    void shouldReturnEmptyWhenNoItemMatches() {
        String html = "<html><body><div class=\"dgControl\"><span>没有 iusc 条目</span></div></body></html>";

        assertTrue(parser.parseImageUrls(html, 10).isEmpty());
    }

    private String readSample() {
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("bing/image-search-sample.html")) {
            if (inputStream == null) {
                throw new IllegalStateException("缺少契约样本 bing/image-search-sample.html");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取契约样本失败", e);
        }
    }
}
