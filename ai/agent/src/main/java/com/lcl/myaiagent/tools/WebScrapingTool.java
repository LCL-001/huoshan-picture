package com.lcl.myaiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;

/**
 * 网页抓取工具类
 * 提供基于Jsoup的网页内容抓取功能，供AI助手调用以获取网页HTML内容
 */
public class WebScrapingTool {

    /**
     * 抓取指定URL的网页内容
     * 使用Jsoup库连接目标网页并获取其完整的HTML文档内容
     *
     * @param url 要抓取的网页URL地址
     * @return String 网页的HTML内容；如果抓取失败则返回错误信息
     */
    @Tool(description = "Scrape the content of a web page")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
            // 连接目标网页并获取HTML文档
            Document doc = Jsoup.connect(url).get();
            return doc.html();
        } catch (IOException e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }
}
