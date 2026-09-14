package com.lcl.myaiagent.tools.huoshan;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 工具装配单测（docs/plan.md T7）：图库助手工具集 = 三个只读工具 + 两个写类工具（档 3），
 * 且**不含**任何删除类与文件/终端类工具（一期不注册删除类工具，整理只增改不删；
 * 图库助手只挂图库工具集 + MCP 搜图，设计文档 L69/L97）。
 */
class HuoshanToolFactoryTest {

    private static HuoshanApiClient client() {
        return new HuoshanApiClient("http://127.0.0.1:1", "satoken", "session",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @Test
    void assistantToolSetIsExactlyTheFiveBusinessTools() {
        Set<String> names = namesOf(HuoshanToolFactory.assistantTools(client()));

        assertThat(names).containsExactlyInAnyOrder(
                "listSpaces", "listPictures", "getTagCategory",
                "batchEditPictures", "batchUploadByUrl");
    }

    @Test
    void assistantToolSetHasNoDeletionOrShellTools() {
        Set<String> names = namesOf(HuoshanToolFactory.assistantTools(client()));

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
