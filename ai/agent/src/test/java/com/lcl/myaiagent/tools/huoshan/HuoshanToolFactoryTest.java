package com.lcl.myaiagent.tools.huoshan;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 工具装配单测（docs/plan.md T7）：图库助手工具集 = 三个只读工具 + 两个写类工具，
 * **配了视觉模型才多挂 visionTagger**；且**不含**任何删除类与文件/终端类工具
 * （一期不注册删除类工具，整理只增改不删；图库助手只挂图库工具集 + MCP 搜图，设计文档 L69/L97）。
 */
class HuoshanToolFactoryTest {

    private static HuoshanApiClient client() {
        return new HuoshanApiClient("http://127.0.0.1:1", "satoken", "session",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    /** 视觉模型桩：本用例只关心装配出来的工具名单，不关心模型回什么 */
    private static ChatModel visionStub() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
            }
        };
    }

    @Test
    void withoutVisionModelTheToolSetIsTheFiveNonVisionTools() {
        Set<String> names = namesOf(HuoshanToolFactory.assistantTools(client(), null));

        assertThat(names)
                .as("没配视觉模型就不挂 visionTagger：宁可让模型看见没有这个工具，也别给它一个每次报错的工具")
                .containsExactlyInAnyOrder(
                        "listSpaces", "listPictures", "getTagCategory",
                        "batchEditPictures", "batchUploadByUrl");
    }

    @Test
    void withVisionModelTheToolSetAddsVisionTagger() {
        Set<String> names = namesOf(HuoshanToolFactory.assistantTools(client(), visionStub()));

        assertThat(names).containsExactlyInAnyOrder(
                "listSpaces", "listPictures", "getTagCategory",
                "batchEditPictures", "batchUploadByUrl", "visionTagger");
    }

    @Test
    void extraToolsSuchAsTheMcpSearchToolAreAppended() {
        ToolCallback[] mcpTools = ToolCallbacks.from(new StubMcpSearchTool());

        Set<String> names = namesOf(HuoshanToolFactory.assistantTools(client(), visionStub(), mcpTools));

        assertThat(names)
                .as("T9：搜图 MCP 服务的工具必须并进图库助手工具集")
                .contains("searchImage")
                .contains("batchUploadByUrl")
                .contains("visionTagger");
    }

    /** 假的 MCP 搜图工具（形状照 ai/image-search-mcp-server 的 searchImage） */
    static class StubMcpSearchTool {

        @Tool(description = "search image from web")
        public String searchImage(@ToolParam(description = "Search query keyword") String query) {
            return "https://img.example/a.jpg";
        }
    }

    @Test
    void assistantToolSetHasNoDeletionOrShellTools() {
        Set<String> names = namesOf(HuoshanToolFactory.assistantTools(client(), visionStub()));

        assertThat(names)
                .as("一期口径：不注册删除类工具，也不把文件/终端类工具挂给图库助手")
                .noneMatch(name -> name.toLowerCase().contains("delete")
                        || name.toLowerCase().contains("remove")
                        || name.toLowerCase().contains("terminal")
                        || name.equals("readFile")
                        || name.equals("writeFile"));
    }

    private static Set<String> namesOf(ToolCallback[] tools) {
        return Arrays.stream(tools)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
