package com.lcl.myaiagent.tools.huoshan;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 拉图片清单（T7 档 1）：只读元数据，供模型归类与用户核对。
 */
public class ListPicturesTool {

    private final HuoshanApiClient client;

    public ListPicturesTool(HuoshanApiClient client) {
        this.client = client;
    }

    @Tool(description = """
            分页拉取某空间下的图片清单（只读元数据，不修改任何数据）。
            返回 JSON：total/current/pageSize/hasMore/items[]，
            items[] 含 id、name、url、thumbnailUrl、category、tags[]、picFormat、picSize、picWidth、picHeight、spaceId。
            """)
    public String listPictures(
            @ToolParam(description = "空间 id，必传：先用 listSpaces 拿到") Long spaceId,
            @ToolParam(description = "页码，从 1 开始", required = false) Integer pageNum,
            @ToolParam(description = "每页条数，默认 10，最大 50", required = false) Integer pageSize,
            @ToolParam(description = "按名称/简介搜索的关键字，可不传", required = false) String searchText,
            @ToolParam(description = "按分类过滤，可不传（分类取值见 getTagCategory）", required = false) String category) {
        return HuoshanToolSupport.render(() -> client.listPictures(spaceId, pageNum, pageSize, searchText, category));
    }
}
