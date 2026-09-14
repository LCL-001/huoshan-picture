package com.lcl.myaiagent.tools.huoshan;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7-lite 工具装配单测（docs/plan.md T7 档 1）：图库助手工具集只含三个只读工具，
 * 且**不含**任何删除/终端类工具（一期不注册删除类工具，整理只增改不删）。
 */
class HuoshanToolFactoryTest {

    @Test
    void readOnlyToolSetIsExactlyTheThreeBusinessTools() {
        HuoshanApiClient client = new HuoshanApiClient("http://127.0.0.1:1", "satoken",
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        ToolCallback[] tools = HuoshanToolFactory.readOnlyTools(client);

        Set<String> names = Arrays.stream(tools)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("listSpaces", "listPictures", "getTagCategory");
    }
}
