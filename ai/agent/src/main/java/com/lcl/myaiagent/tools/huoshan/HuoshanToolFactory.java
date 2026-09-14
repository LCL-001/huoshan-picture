package com.lcl.myaiagent.tools.huoshan;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * 图库助手工具集装配（T7 档 1）：工具是 prototype——每次请求按调用者 satoken 新建实例，
 * 不做单例（token 只在请求生命周期，不能跨请求复用）。档 1 只注册三个只读工具，
 * 一期不注册删除类工具（整理只增改不删，设计文档 L97）。
 */
public final class HuoshanToolFactory {

    private HuoshanToolFactory() {
    }

    public static ToolCallback[] readOnlyTools(HuoshanApiClient client) {
        return ToolCallbacks.from(
                new ListSpacesTool(client),
                new ListPicturesTool(client),
                new GetTagCategoryTool(client)
        );
    }
}
