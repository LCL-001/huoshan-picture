<template>
  <a-card :body-style="{ padding: 0 }" class="skeleton-card">
    <div class="skeleton-cover">
      <div class="skeleton-img skeleton-shimmer" />
    </div>
    <div class="skeleton-body">
      <div class="skeleton-line short skeleton-shimmer" />
      <div class="skeleton-line full skeleton-shimmer" />
    </div>
  </a-card>
</template>

<script setup lang="ts">
</script>

<style scoped>
.skeleton-card {
  border: none;
  box-shadow: none;
  overflow: hidden;
  background: transparent;
}

.skeleton-cover {
  background: #f0f5ff;
}

/* shimmer 渐变与动画统一复用全局 .skeleton-shimmer（animations.css） */
.skeleton-img {
  width: 100%;
  aspect-ratio: 4 / 3;
}

.skeleton-body {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skeleton-line {
  height: 10px;
  border-radius: 4px;
}

.skeleton-line.short {
  width: 60%;
}

.skeleton-line.full {
  width: 100%;
}
</style>
