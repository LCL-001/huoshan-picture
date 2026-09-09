<template>
  <router-view v-slot="{ Component }">
    <Transition :name="transitionName" mode="out-in">
      <KeepAlive :include="cachedPages" :max="8">
        <component :is="Component" :key="route.path" />
      </KeepAlive>
    </Transition>
  </router-view>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

// 浏览类列表页纳入 KeepAlive：从图片详情返回时复用页面实例，
// 不再重新请求数据、重放骨架屏与入场动画（页面内配合 onActivated 静默刷新）
const cachedPages = ['HomePage', 'SpaceDetailPage']

const transitionName = computed(() => {
  const path = route.path
  // 详情页从右侧滑入，营造"进入"感
  if (path.startsWith('/picture/') || path.startsWith('/post/')) {
    return 'page-slide-right'
  }
  // 其他页面统一淡入
  return 'page-fade'
})
</script>

<style scoped>
/* 淡入 */
.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity 0.3s ease;
}
.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
}

/* 从右侧滑入 */
.page-slide-right-enter-active,
.page-slide-right-leave-active {
  transition: transform 0.3s ease, opacity 0.3s ease;
}
.page-slide-right-enter-from {
  opacity: 0;
  transform: translateX(40px);
}
.page-slide-right-leave-to {
  opacity: 0;
  transform: translateX(-40px);
}
</style>
