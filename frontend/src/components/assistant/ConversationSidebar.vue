<template>
  <aside class="conversation-sidebar">
    <div class="conversation-sidebar__head">
      <span class="conversation-sidebar__label">历史会话</span>
      <a-button size="small" @click="$emit('newChat')">新对话</a-button>
    </div>
    <a-spin v-if="loading" size="small" />
    <p v-else-if="!conversations.length" class="conversation-sidebar__empty">
      还没有会话，发第一条消息试试
    </p>
    <ul v-else class="conversation-sidebar__list">
      <li
        v-for="item in conversations"
        :key="item.chatId"
        :class="{ 'conversation-sidebar__item--active': item.chatId === activeChatId }"
        @click="$emit('select', item.chatId)"
      >
        <span class="conversation-sidebar__title">{{ item.title }}</span>
        <span class="conversation-sidebar__time">{{ formatDay(item.updateTime) }}</span>
      </li>
    </ul>
  </aside>
</template>

<script setup lang="ts">
import type { AssistantConversationItem } from '@/api/assistantConversation.ts'

defineProps<{
  conversations: AssistantConversationItem[]
  activeChatId: string
  loading: boolean
}>()

defineEmits<{ select: [chatId: string]; newChat: [] }>()

/** 列表项只显示日期，时间戳是 ISO 字符串，解析失败退回原文 */
const formatDay = (value: string) => {
  const day = new Date(value)
  return Number.isNaN(day.getTime()) ? value : day.toLocaleDateString()
}
</script>

<style scoped>
.conversation-sidebar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 240px;
  flex-shrink: 0;
  padding: 12px;
  border: 1px solid var(--app-border-soft);
  border-radius: var(--app-radius-md);
  background: var(--app-surface);
}

.conversation-sidebar__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.conversation-sidebar__label {
  font-size: 13px;
  font-weight: 600;
}

.conversation-sidebar__empty {
  margin: 0;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.conversation-sidebar__list {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 52vh;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.conversation-sidebar__list li {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 8px;
  border-radius: var(--app-radius-sm);
  cursor: pointer;
}

.conversation-sidebar__list li:hover {
  background: var(--app-primary-softer);
}

.conversation-sidebar__item--active {
  background: var(--app-primary-soft);
}

.conversation-sidebar__title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-sidebar__time {
  font-size: 11px;
  color: var(--app-text-secondary);
}

@media (max-width: 768px) {
  .conversation-sidebar {
    width: auto;
  }
}
</style>
