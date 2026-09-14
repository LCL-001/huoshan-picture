package com.lcl.myaiagent.tools.huoshan;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * 图库助手工具集装配（T7）：工具是 prototype——每次请求按调用者凭据组新建实例，
 * 不做单例（凭据只在请求生命周期，不能跨请求复用）。
 * <p>
 * 档 3 起含写类工具（{@link BatchEditPicturesTool} / {@link BatchUploadByUrlTool}）；
 * 一期**不注册**删除类工具（整理只增改不删，设计文档 L97），也不挂文件/终端类工具
 * （图库助手只挂图库工具集 + MCP 搜图，设计文档 L69）。
 * </p>
 */
public final class HuoshanToolFactory {

    private HuoshanToolFactory() {
    }

    public static ToolCallback[] assistantTools(HuoshanApiClient client) {
        return ToolCallbacks.from(
                new ListSpacesTool(client),
                new ListPicturesTool(client),
                new GetTagCategoryTool(client),
                new BatchEditPicturesTool(client),
                new BatchUploadByUrlTool(client)
        );
    }
}
