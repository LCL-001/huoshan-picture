package com.lcl.myaiagent.tools.huoshan;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * 图库助手工具集装配（T7）：工具是 prototype——每次请求按调用者凭据组新建实例，
 * 不做单例（凭据只在请求生命周期，不能跨请求复用）。
 * <p>
 * 档 3 起含写类工具（{@link BatchEditPicturesTool} / {@link BatchUploadByUrlTool}）与看图打标
 * （{@link VisionTaggerTool}）；一期**不注册**删除类工具（整理只增改不删，设计文档 L97），
 * 也不挂文件/终端类工具（图库助手只挂图库工具集 + MCP 搜图，设计文档 L69）。
 * </p>
 */
public final class HuoshanToolFactory {

    private HuoshanToolFactory() {
    }

    /**
     * @param visionModel 多模态模型（见 OpenAiChatModels.vision()）；**为 null 时不挂 visionTagger**——
     *                    没配视觉模型就该让模型看到"手上没有这个工具"，而不是给它一个每次都报错的工具
     */
    public static ToolCallback[] assistantTools(HuoshanApiClient client, ChatModel visionModel) {
        List<Object> tools = new ArrayList<>(List.of(
                new ListSpacesTool(client),
                new ListPicturesTool(client),
                new GetTagCategoryTool(client),
                new BatchEditPicturesTool(client),
                new BatchUploadByUrlTool(client)
        ));
        if (visionModel != null) {
            tools.add(new VisionTaggerTool(client, visionModel));
        }
        return ToolCallbacks.from(tools.toArray());
    }
}
