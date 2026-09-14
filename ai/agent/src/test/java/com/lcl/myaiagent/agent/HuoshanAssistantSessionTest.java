package com.lcl.myaiagent.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T8-lite 身份映射单测（docs/plan.md T8 档 1）：外部用户标识 → 会话归属前缀；
 * 会话 id 由归属 + chatId 确定性派生（同一用户同一会话稳定续聊），且**跨用户不可读**
 * （换个 userId 就落到另一个会话 id），长度稳定落在 conversation.id varchar(64) 内。
 */
class HuoshanAssistantSessionTest {

    @Test
    void mapsExternalUserIdToNamespacedOwner() {
        assertThat(HuoshanAssistantSession.owner("123")).isEqualTo("huoshan:123");
        assertThat(HuoshanAssistantSession.owner(" 42 ")).isEqualTo("huoshan:42");
    }

    @Test
    void rejectsBlankOrMalformedExternalUserId() {
        assertThatThrownBy(() -> HuoshanAssistantSession.owner(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HuoshanAssistantSession.owner("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HuoshanAssistantSession.owner("abc"))
                .as("图库 userId 是数字")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivesStableConversationIdWithinColumnWidth() {
        String owner = HuoshanAssistantSession.owner("123");

        String first = HuoshanAssistantSession.conversationId(owner, "chat-1");
        String second = HuoshanAssistantSession.conversationId(owner, "chat-1");
        String other = HuoshanAssistantSession.conversationId(owner, "chat-2");

        assertThat(first).isEqualTo(second).as("同一用户同一 chatId 必须落到同一会话，记忆才能续上");
        assertThat(first).isNotEqualTo(other);
        assertThat(first).hasSize(36).as("定长 UUID，适配 conversation.id varchar(64)");
    }

    @Test
    void sameChatIdFromDifferentOwnersLandsOnDifferentConversations() {
        String ownerA = HuoshanAssistantSession.owner("123");
        String ownerB = HuoshanAssistantSession.owner("456");

        assertThat(HuoshanAssistantSession.conversationId(ownerA, "chat-1"))
                .as("别的图库用户拿到同一个 chatId 也读不到本用户的记忆")
                .isNotEqualTo(HuoshanAssistantSession.conversationId(ownerB, "chat-1"));
    }

    @Test
    void generatesRandomConversationWhenChatIdMissing() {
        String owner = HuoshanAssistantSession.owner("123");

        String first = HuoshanAssistantSession.conversationId(owner, null);
        String second = HuoshanAssistantSession.conversationId(owner, "  ");

        assertThat(first).hasSize(36);
        assertThat(first).isNotEqualTo(second).as("没有 chatId 时每次是独立新会话");
    }
}
