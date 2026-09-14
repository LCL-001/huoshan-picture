<template>
  <div class="assistant-text">
    <template v-for="(block, index) in blocks" :key="index">
      <ul v-if="block.kind === 'bullet'" class="assistant-text__list">
        <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
          <AssistantInline :parts="item" />
        </li>
      </ul>
      <ol v-else-if="block.kind === 'ordered'" class="assistant-text__list">
        <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
          <AssistantInline :parts="item" />
        </li>
      </ol>
      <blockquote v-else-if="block.kind === 'quote'" class="assistant-text__quote">
        <p v-for="(item, itemIndex) in block.items" :key="itemIndex">
          <AssistantInline :parts="item" />
        </p>
      </blockquote>
      <p v-else class="assistant-text__paragraph">
        <AssistantInline :parts="block.items[0]" />
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AssistantInline from '@/components/assistant/AssistantInline.vue'
import { parseAssistantText } from '@/utils/assistantFormat.ts'

const props = defineProps<{
  text: string
}>()

const blocks = computed(() => parseAssistantText(props.text))
</script>

<style scoped>
.assistant-text__paragraph {
  margin: 0 0 8px;
}

.assistant-text__list {
  margin: 0 0 8px;
  padding-left: 20px;
}

.assistant-text__quote {
  margin: 0 0 8px;
  padding: 2px 0 2px 10px;
  border-left: 3px solid var(--app-border);
  color: var(--app-text-secondary);
}

.assistant-text__quote p {
  margin: 0;
}

.assistant-text__paragraph:last-child,
.assistant-text__list:last-child,
.assistant-text__quote:last-child {
  margin-bottom: 0;
}
</style>
