<template>
  <div v-if="DEV" class="dev-status-bar">
    <span class="bar-item bar-dir">{{ projectDir }}</span>
    <span class="bar-separator">|</span>
    <span class="bar-item bar-route">{{ currentRoute }}</span>
    <span class="bar-separator">|</span>
    <span class="bar-item bar-time">{{ buildTime }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const DEV = import.meta.env.DEV
const route = useRoute()
const buildTime = import.meta.env.VITE_BUILD_TIME ?? 'dev'

const currentRoute = computed(() => route.path)
const projectDir = 'yu-picture-frontend'
</script>

<style scoped>
.dev-status-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  padding: 0 12px;
  background: #1e293b;
  color: #94a3b8;
  font-size: 11px;
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  letter-spacing: 0.02em;
  z-index: 9999;
  position: sticky;
  top: 0;
  user-select: none;
}

.bar-item {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bar-dir {
  color: #64748b;
}

.bar-route {
  color: #e2e8f0;
  font-weight: 600;
}

.bar-time {
  color: #64748b;
}

.bar-separator {
  color: #334155;
  font-size: 10px;
}
</style>
