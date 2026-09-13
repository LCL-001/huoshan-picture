package com.lcl.myaiagent.config;

import com.lcl.myaiagent.tools.AskHumanTool;
import com.lcl.myaiagent.tools.FileOperationTool;
import com.lcl.myaiagent.tools.PDFGenerationTool;
import com.lcl.myaiagent.tools.ResourceDownloadTool;
import com.lcl.myaiagent.tools.TerminateTool;
import com.lcl.myaiagent.tools.WebScrapingTool;
import com.lcl.myaiagent.tools.WebSearchTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the original MyManus tool callbacks.
 */
@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key:}")
    private String searchApiKey;

    @Bean
    public ToolCallback[] allTools() {
        return ToolCallbacks.from(
                new FileOperationTool(),
                new WebSearchTool(searchApiKey),
                new WebScrapingTool(),
                new ResourceDownloadTool(),
                new PDFGenerationTool(),
                new AskHumanTool(),
                new TerminateTool()
        );
    }
}
