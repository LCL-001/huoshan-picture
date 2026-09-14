<template>
  <a-collapse
    v-if="steps.length || running"
    class="step-timeline"
    :bordered="false"
    :default-active-key="['steps']"
  >
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
          <!-- 工具步骤只给一句话摘要，原始返回收进「详情」；见 ToolStepRow -->
          <ToolStepRow v-if="step.kind === 'tool'" :name="step.name" :content="step.content" />
          <template v-else>
            <span class="step-timeline__label">思考</span>
            <div class="step-timeline__think">
              <AssistantText :text="step.content" />
            </div>
          </template>
        </div>
      </div>
    </a-collapse-panel>
  </a-collapse>
</template>

<script setup lang="ts">
import { CheckCircleOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import AssistantText from '@/components/assistant/AssistantText.vue'
import ToolStepRow from '@/components/assistant/ToolStepRow.vue'
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
  background: var(--app-primary-softer);
  color: var(--app-text-secondary);
  font-size: 12px;
  line-height: 18px;
}

.step-timeline__think {
  padding-left: 10px;
  border-left: 2px solid var(--app-border-soft);
  color: var(--app-text-secondary);
  font-size: 13px;
}
</style>
