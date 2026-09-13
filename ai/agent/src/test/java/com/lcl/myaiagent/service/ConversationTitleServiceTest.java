package com.lcl.myaiagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationTitleServiceTest {

    @Test
    void shouldNormalizeAndLimitGeneratedTitle() {
        assertEquals("Spring 事务失效排查", ConversationTitleService.normalizeTitle("\n“Spring 事务失效排查”\n"));
        assertEquals("Java 面试事务与索引问题及更多内", ConversationTitleService.normalizeTitle("Java 面试事务与索引问题及更多内容"));
        assertNull(ConversationTitleService.normalizeTitle("   "));
    }
}
