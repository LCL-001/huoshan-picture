<template>
  <a-collapse v-if="steps.length || running" class="step-timeline" :bordered="false" :default-active-key="['steps']">
    <a-collapse-panel key="steps">
      <template #header>
        <span class="step-timeline__head">
          <LoadingOutlined v-if="running" spin />
          <CheckCircleOutlined v-else />
          {{ running ? `正在执行（${steps.length} 步）` : `执行步骤（${steps.length} 步）` }}
        </span>
      </template>
      <div class="step-timeline__body">
        <div v-for="(step, index) in steps" :key="index" class="step-timeline__item">
          <span class="step-timeline__label" :class="`step-timeline__label--${step.kind}`">
            {{ step.kind === 'tool' ? `工具 · ${step.name || '未知'}` : '思考' }}
          </span>
          <pre class="step-timeline__content">{{ step.content }}</pre>
        </div>
      </div>
    </a-collapse-panel>
  </a-collapse>
</template>

<script setup lang="ts">
import { CheckCircleOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import type { AssistantStep } from '@/utils/assistantSse.ts'

defineProps<{
  steps: AssistantStep[]
  running: boolean
}>()
</script>

<style scoped>
.step-timeline {
  margin: 8px 0;
  background: transparent;
}

.step-timeline__head {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--app-text-secondary);
  font-size: 13px;
}

.step-timeline__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.step-timeline__item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.step-timeline__label {
  align-self: flex-start;
  padding: 1px 8px;
  border-radius: var(--app-radius-sm);
  font-size: 12px;
  line-height: 18px;
}

.step-timeline__label--think {
  color: var(--app-text-secondary);
  background: var(--app-primary-softer);
}

.step-timeline__label--tool {
  color: var(--app-primary);
  background: var(--app-primary-soft);
}

.step-timeline__content {
  margin: 0;
  padding: 6px 10px;
  max-height: 200px;
  overflow: auto;
  border: 1px solid var(--app-border-soft);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  color: var(--app-text);
  font-size: 12px;
  font-family: inherit;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
