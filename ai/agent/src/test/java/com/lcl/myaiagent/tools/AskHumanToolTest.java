package com.lcl.myaiagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AskHumanToolTest {

    @Test
    void askHumanShouldReturnClarificationSignal() {
        AskHumanTool tool = new AskHumanTool();

        String result = tool.askHuman("请提供目标网站地址");

        assertEquals(AskHumanTool.ASK_HUMAN_PREFIX + "请提供目标网站地址", result);
    }
}
