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
 * 首次公开部署阶段只装配三个只读工具与看图打标
 * （{@link VisionTaggerTool}）；一期**不注册**删除类工具（整理只增改不删，设计文档 L97），
 * 也不挂文件/终端类工具（图库助手只挂图库工具集 + MCP 搜图，设计文档 L69）。
 * </p>
 * <p>
 * T17 起**看图打标只挂给管理员会话**（用户 2026-09-15 拍板：普通用户的助手摘掉只读打标，
 * 打标是管理员能力）。两个写工具的实现暂时保留，但在确定性用户确认门完成前不装配给模型。
 * </p>
 */
public final class HuoshanToolFactory {

    private HuoshanToolFactory() {
    }

    /**
     * @param visionModel     多模态模型（见 OpenAiChatModels.vision()）；**为 null 时不挂 visionTagger**——
     *                        没配视觉模型就该让模型看到"手上没有这个工具"，而不是给它一个每次都报错的工具
     * @param canTagPictures  调用者是否管理员（见 {@code HuoshanAssistantSession.isAdmin}）：为 false 时不挂
     *                        visionTagger——普通用户的助手看不到、也调不到看图打标（T17）
     * @param extraTools      额外工具（T9：搜图 MCP 服务的工具回调）；MCP 没配就是空数组
     */
    public static ToolCallback[] assistantTools(HuoshanApiClient client, ChatModel visionModel,
                                                boolean canTagPictures, ToolCallback... extraTools) {
        List<Object> tools = new ArrayList<>(List.of(
                new ListSpacesTool(client),
                new ListPicturesTool(client),
                new GetTagCategoryTool(client)
        ));
        if (visionModel != null && canTagPictures) {
            tools.add(new VisionTaggerTool(client, visionModel));
        }
        List<ToolCallback> callbacks = new ArrayList<>(List.of(ToolCallbacks.from(tools.toArray())));
        if (extraTools != null && extraTools.length > 0) {
            callbacks.addAll(List.of(extraTools));
        }
        return callbacks.toArray(new ToolCallback[0]);
    }
}
