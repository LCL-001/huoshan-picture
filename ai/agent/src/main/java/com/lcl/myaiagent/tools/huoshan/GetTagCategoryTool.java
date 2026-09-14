package com.lcl.myaiagent.tools.huoshan;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 取动态词表（T7 档 1）：打标前必须先看这份受治理的词表，优先复用已有标签。
 */
public class GetTagCategoryTool {

    private final HuoshanApiClient client;

    public GetTagCategoryTool(HuoshanApiClient client) {
        this.client = client;
    }

    @Tool(description = """
            获取图库当前的标签与分类词表（受治理的全局词表，实时来自图库）。
            整理/打标前先调用它，优先复用已有标签；词表确实缺了才提议新标签。
            返回 JSON：tagList[]、categoryList[]。
            """)
    public String getTagCategory() {
        return HuoshanToolSupport.render(client::getTagCategory);
    }
}
