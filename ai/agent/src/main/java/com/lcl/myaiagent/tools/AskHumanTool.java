package com.lcl.myaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Tool for asking the user to provide missing information.
 */
public class AskHumanTool {

    public static final String ASK_HUMAN_PREFIX = "[ASK_HUMAN]";

    @Tool(description = """
            Ask the user for missing information when the task cannot continue safely or accurately.
            Use this tool only when a specific user clarification is required.
            """)
    public String askHuman(@ToolParam(description = "The specific question to ask the user") String inquire) {
        return ASK_HUMAN_PREFIX + inquire;
    }
}
