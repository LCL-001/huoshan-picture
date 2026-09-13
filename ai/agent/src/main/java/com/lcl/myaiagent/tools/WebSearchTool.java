package com.lcl.myaiagent.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.Map;

/**
 * 网络搜索工具类
 * 提供基于SearchAPI的网络搜索功能，支持通过百度搜索引擎获取信息
 * 供AI助手调用以获取实时网络数据
 */
public class WebSearchTool {

    /**
     * SearchAPI的搜索接口地址
     * 用于发送搜索请求并获取搜索结果
     */
    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    /**
     * SearchAPI访问密钥
     * 用于身份验证和授权搜索请求
     */
    private final String apiKey;

    /**
     * 构造网络搜索工具实例
     *
     * @param apiKey SearchAPI的访问密钥，用于认证搜索请求
     */
    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 执行网络搜索操作
     * 通过SearchAPI调用百度搜索引擎，获取与查询关键词相关的搜索结果
     * 返回前5条最相关的搜索结果，格式化为JSON字符串
     *
     * @param query 搜索查询关键词，用于指定要搜索的内容
     * @return String 搜索结果的JSON字符串，包含前5条有机搜索结果；如果搜索失败则返回错误信息
     */
    @Tool(description = "Search for information from Baidu Search Engine")
    public String searchWeb(
            @ToolParam(description = "Search query keyword") String query) {
        // Key 缺失时明确告知配置方法，避免模型反复重试
        if (StrUtil.isBlank(apiKey)) {
            return "Error searching Baidu: Search API key is not configured. "
                    + "Please set the SEARCH_API_KEY environment variable and restart the application.";
        }
        // 构建搜索请求参数，包括查询词、API密钥和搜索引擎类型
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");
        try {
            // 显式超时，避免单次工具调用长时间挂起拖垮整个执行步骤
            String response = HttpUtil.get(SEARCH_API_URL, paramMap, 15000);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            // Key 无效、额度用尽等场景接口会返回 error 字段，把真实原因交给模型决策
            String apiError = jsonObject.getStr("error");
            if (StrUtil.isNotBlank(apiError)) {
                return "Error searching Baidu: " + apiError;
            }
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            if (organicResults == null || organicResults.isEmpty()) {
                return "No results found for: " + query;
            }
            // 输出结构化文本（标题/摘要/日期/链接），便于模型直接阅读和引用
            StringBuilder result = new StringBuilder();
            int limit = Math.min(5, organicResults.size());
            for (int i = 0; i < limit; i++) {
                JSONObject item = (JSONObject) organicResults.get(i);
                result.append(i + 1).append(". ").append(item.getStr("title", ""));
                String snippet = item.getStr("snippet");
                if (StrUtil.isNotBlank(snippet)) {
                    result.append(" - ").append(snippet);
                }
                String date = item.getStr("date");
                if (StrUtil.isNotBlank(date)) {
                    result.append(" (").append(date).append(')');
                }
                result.append("\n   ").append(item.getStr("link", ""));
                if (i < limit - 1) {
                    result.append('\n');
                }
            }
            return result.toString();
        } catch (Exception e) {
            return "Error searching Baidu: " + e.getMessage();
        }
    }
}
