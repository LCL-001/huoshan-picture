package com.lcl.myaiagent.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCallAgentLoggingTest {

    @Test
    void infoLogsContainToolNamesButNotArgumentsOrResults() {
        String argumentMarker = "SECRET-ARG\\nFORGED-ARG-LINE";
        String resultMarker = "SECRET-RESULT\\nFORGED-RESULT-LINE";
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "FUNCTION", "listSpaces", "{\"query\":\"" + argumentMarker + "\"}");
        AssistantMessage assistantMessage = mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn("thinking");
        when(assistantMessage.getToolCalls()).thenReturn(List.of(toolCall));
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(assistantMessage);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);

        ToolCallingManager toolManager = mock(ToolCallingManager.class);
        ToolResponseMessage responseMessage = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "listSpaces", resultMarker)))
                .build();
        ToolExecutionResult executionResult = mock(ToolExecutionResult.class);
        when(executionResult.conversationHistory()).thenReturn(List.of(assistantMessage, responseMessage));
        when(toolManager.executeToolCalls(any(), any())).thenReturn(executionResult);

        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]) {
            @Override
            protected ChatResponse callLlm(Prompt prompt) {
                return response;
            }
        };
        agent.setName("LoggingProbe");
        agent.setToolCallingManager(toolManager);

        Logger logger = (Logger) LoggerFactory.getLogger(ToolCallAgent.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(agent.think()).isTrue();
            agent.act();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("listSpaces"))
                .allMatch(message -> !message.contains("SECRET-ARG")
                        && !message.contains("SECRET-RESULT")
                        && !message.contains("FORGED-"));
    }
}
