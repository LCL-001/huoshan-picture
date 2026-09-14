<template>
  <div class="tool-step">
    <div class="tool-step__head">
      <span class="tool-step__label">工具 · {{ label }}</span>
      <span class="tool-step__summary">{{ summary }}</span>
      <a-button type="link" size="small" class="tool-step__toggle" @click="rawVisible = !rawVisible">
        {{ rawVisible ? '收起' : '详情' }}
      </a-button>
    </div>
    <pre v-if="rawVisible" class="tool-step__raw">{{ content }}</pre>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { summarizeToolResult, toolLabel } from '@/utils/assistantFormat.ts'

const props = defineProps<{
  name?: string
  content: string
}>()

/** 原始返回默认收起：那是给开发排查用的，普通用户只需要上面那句摘要 */
const rawVisible = ref(false)
const label = computed(() => toolLabel(props.name))
const summary = computed(() => summarizeToolResult(props.content, props.name))
</script>

<style scoped>
.tool-step {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tool-step__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-step__label {
  flex: none;
  padding: 1px 8px;
  border-radius: var(--app-radius-sm);
  background: var(--app-primary-soft);
  color: var(--app-primary);
  font-size: 12px;
  line-height: 18px;
}

.tool-step__summary {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--app-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-step__toggle {
  flex: none;
  height: 20px;
  padding: 0 4px;
  font-size: 12px;
}

.tool-step__raw {
  margin: 0;
  padding: 6px 10px;
  max-height: 200px;
  overflow: auto;
  border: 1px solid var(--app-border-soft);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  color: var(--app-text);
  font-size: 12px;
  font-family: ui-monospace, Consolas, 'Courier New', monospace;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
