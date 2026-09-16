package com.lcl.myaiagent.advisors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MyLoggerAdvisorTest {

    @Test
    void infoLogsDoNotContainPromptOrModelResponseText() {
        String marker = "SENSITIVE\\nFORGED-LOG-LINE";
        ChatClientRequest request = mock(ChatClientRequest.class);
        Prompt prompt = mock(Prompt.class);
        when(prompt.toString()).thenReturn(marker);
        when(request.prompt()).thenReturn(prompt);

        ChatClientResponse response = mock(ChatClientResponse.class, RETURNS_DEEP_STUBS);
        when(response.chatResponse().getResult().getOutput().getText()).thenReturn(marker);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(response);

        Logger logger = (Logger) LoggerFactory.getLogger(MyLoggerAdvisor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new MyLoggerAdvisor().adviseCall(request, chain);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allMatch(message -> !message.contains("SENSITIVE") && !message.contains("FORGED-LOG-LINE"));
    }
}
