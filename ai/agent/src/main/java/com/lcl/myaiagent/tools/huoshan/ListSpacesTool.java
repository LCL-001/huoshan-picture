package com.lcl.myaiagent.tools.huoshan;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 列出可见空间（T7 档 1）：整理图片前先拿空间 id 与容量水位。
 */
public class ListSpacesTool {

    private final HuoshanApiClient client;

    public ListSpacesTool(HuoshanApiClient client) {
        this.client = client;
    }

    @Tool(description = """
            列出当前用户可见的图库空间（分页）。需要空间 id 时先调用它。
            返回 JSON：total/current/pageSize/hasMore/items[]，
            items[] 含 id、spaceName、spaceType(0-私有 1-团队)、spaceLevel(0-普通 1-专业 2-旗舰)、
            totalCount/maxCount（已用/上限图片数）、permissionList（当前用户权限点）。
            """)
    public String listSpaces(
            @ToolParam(description = "页码，从 1 开始", required = false) Integer pageNum,
            @ToolParam(description = "每页条数，默认 10，最大 50", required = false) Integer pageSize,
            @ToolParam(description = "空间名称关键字，可不传", required = false) String spaceName,
            @ToolParam(description = "空间类型：0-私有 1-团队，可不传", required = false) Integer spaceType) {
        return HuoshanToolSupport.render(() -> client.listSpaces(pageNum, pageSize, spaceName, spaceType));
    }
}
