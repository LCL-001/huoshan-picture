<template>
  <template v-for="(part, index) in parts" :key="index">
    <strong v-if="part.type === 'strong'">{{ part.value }}</strong>
    <code v-else-if="part.type === 'code'" class="assistant-inline__code">{{ part.value }}</code>
    <a
      v-else-if="part.type === 'link'"
      class="assistant-inline__link"
      :href="part.href"
      target="_blank"
      rel="noopener noreferrer"
    >
      {{ part.value }}
    </a>
    <template v-else>{{ part.value }}</template>
  </template>
</template>

<script setup lang="ts">
import type { InlinePart } from '@/utils/assistantFormat.ts'

defineProps<{
  parts: InlinePart[]
}>()
</script>

<style scoped>
.assistant-inline__code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--app-primary-softer);
  color: var(--app-primary);
  font-size: 12px;
  font-family: ui-monospace, Consolas, 'Courier New', monospace;
}

.assistant-inline__link {
  word-break: break-all;
}
</style>
