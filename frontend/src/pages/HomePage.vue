<template>
  <div id="homePage">
    <!-- 搜索框 -->
    <div class="search-bar">
      <a-input-search
        class="home-search-input"
        v-model:value="searchParams.searchText"
        placeholder="从海量图片中搜索"
        enter-button="搜索"
        size="large"
        @search="doSearch"
      />
    </div>
    <!-- 分类和标签筛选 -->
    <a-tabs v-model:active-key="selectedCategory" @change="doSearch">
      <a-tab-pane key="all" tab="全部" />
      <a-tab-pane v-for="category in categoryList" :tab="category" :key="category" />
    </a-tabs>
    <div class="tag-bar">
      <span class="tag-label">标签：</span>
      <a-space :size="[0, 8]" wrap>
        <a-checkable-tag
          v-for="(tag, index) in tagList"
          :key="tag"
          v-model:checked="selectedTagList[index]"
          @change="doSearch"
        >
          {{ tag }}
        </a-checkable-tag>
      </a-space>
    </div>
    <!-- 骨架屏 / 图片列表 -->
    <div v-if="loading && !dataList.length" class="skeleton-grid">
      <SkeletonCard v-for="i in 10" :key="i" />
    </div>
    <Transition v-else name="list-fade" mode="out-in">
      <PictureList
        :dataList="dataList"
        :loading="loading"
        :animateCards="true"
        :key="'list-' + selectedCategory + '-' + selectedTagList.join(',')"
      />
    </Transition>
    <!-- 滚动加载 -->
    <div style="text-align: center; padding: 24px 0">
      <a-spin v-if="loadingMore" size="small" />
      <span v-else-if="!hasMore && dataList.length" class="no-more">— 没有更多了 —</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref } from 'vue'
import {
  listPictureTagCategoryUsingGet,
  listPictureVoByPageWithCacheUsingPost,
} from '@/api/pictureController.ts'
import { message } from 'ant-design-vue'
import PictureList from '@/components/PictureList.vue'
import SkeletonCard from '@/components/SkeletonCard.vue'

// KeepAlive 缓存标识（见 PageTransition 的 cachedPages）
defineOptions({ name: 'HomePage' })

// 定义数据
const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const loading = ref(true)

// 搜索条件
const searchParams = reactive<API.PictureQueryRequest>({
  current: 1,
  pageSize: 12,
  sortField: 'createTime',
  sortOrder: 'descend',
})

const loadingMore = ref(false)
const hasMore = ref(true)

// 获取数据；silent 用于 KeepAlive 激活时的静默刷新，不展示骨架屏
const fetchData = async (append = false, silent = false) => {
  if (!append) {
    if (!silent) {
      loading.value = true
    }
    searchParams.current = 1
  } else {
    loadingMore.value = true
  }
  const params = {
    ...searchParams,
    tags: [] as string[],
  }
  if (selectedCategory.value !== 'all') {
    params.category = selectedCategory.value
  }
  selectedTagList.value.forEach((useTag, index) => {
    if (useTag) {
      params.tags.push(tagList.value[index])
    }
  })
  const res = await listPictureVoByPageWithCacheUsingPost(params)
  if (res.data.code === 0 && res.data.data) {
    const records = res.data.data.records ?? []
    if (append) {
      dataList.value.push(...records)
    } else if (silent) {
      // 静默刷新：仅把新出现的图片插入头部，保留已加载分页，避免滚动位置塌陷
      const seen = new Set(dataList.value.map((p) => String(p.id)))
      const fresh = records.filter((p) => !seen.has(String(p.id)))
      if (fresh.length > 0) {
        dataList.value = [...fresh, ...dataList.value]
      }
    } else {
      dataList.value = records
    }
    total.value = res.data.data.total ?? 0
    hasMore.value = dataList.value.length < total.value
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
  loading.value = false
  loadingMore.value = false
}

const onScroll = () => {
  const scrollBottom = window.innerHeight + window.scrollY
  const threshold = document.documentElement.scrollHeight - 200
  if (scrollBottom >= threshold && hasMore.value && !loadingMore.value && !loading.value) {
    searchParams.current++
    fetchData(true)
  }
}

onMounted(() => {
  fetchData()
  getTagCategoryOptions()
})

// KeepAlive 缓存下离开页面不会 unmount，滚动监听必须跟激活状态绑定，
// 否则在其他页面滚动会误触发缓存页的无限滚动加载
onActivated(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
})

onDeactivated(() => {
  window.removeEventListener('scroll', onScroll)
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
})

// 从详情页等返回时静默刷新数据（首次挂载已由 onMounted 请求过，跳过避免重复）
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  fetchData(false, true)
})
const doSearch = () => {
  searchParams.current = 1
  fetchData()
}

// 标签和分类列表
const categoryList = ref<string[]>([])
const selectedCategory = ref<string>('all')
const tagList = ref<string[]>([])
const selectedTagList = ref<boolean[]>([])

/**
 * 获取标签和分类选项
 * @param values
 */
const getTagCategoryOptions = async () => {
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    tagList.value = res.data.data.tagList ?? []
    categoryList.value = res.data.data.categoryList ?? []
  } else {
    message.error('获取标签分类列表失败，' + res.data.message)
  }
}
</script>

<style scoped>
#homePage {
  margin-bottom: 16px;
}

#homePage .search-bar {
  max-width: 480px;
  margin: 0 auto 16px;
  position: relative;
  animation: searchArea-enter 0.5s cubic-bezier(0.34, 1.56, 0.64, 1) both;
  animation-delay: 0.1s;
}

#homePage .search-bar :deep(.home-search-input .ant-input-group) {
  display: flex;
  gap: 10px;
}

#homePage .search-bar :deep(.home-search-input .ant-input) {
  height: 42px;
  border-radius: 12px !important;
}

#homePage .search-bar :deep(.home-search-input .ant-input-group-addon) {
  width: auto;
  background: transparent;
}

#homePage .search-bar :deep(.home-search-input .ant-input-group-addon .ant-btn) {
  height: 42px;
  min-width: 88px;
  border-radius: 12px !important;
}

#homePage .tag-bar {
  margin-bottom: 16px;
}

#homePage .tag-label {
  color: var(--app-text-secondary);
  font-size: 13px;
  margin-right: 8px;
}

/* 搜索框背后聚光灯光晕 */
#homePage .search-bar::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  width: 120%;
  height: 200%;
  transform: translate(-50%, -50%);
  background: radial-gradient(
    ellipse at center,
    rgba(22, 119, 255, 0.08) 0%,
    rgba(22, 119, 255, 0.03) 40%,
    transparent 70%
  );
  pointer-events: none;
  z-index: -1;
  transition: opacity 0.4s ease, width 0.4s ease, height 0.4s ease;
  opacity: 0.6;
}

#homePage .search-bar:focus-within::before {
  opacity: 1;
  width: 140%;
  height: 220%;
}

/* 聚焦时输入区增亮提纯（玻璃感） */
#homePage .search-bar :deep(.home-search-input .ant-input-affix-wrapper),
#homePage .search-bar :deep(.home-search-input .ant-input) {
  background: rgba(255, 255, 255, 0.66);
}

#homePage .search-bar:focus-within :deep(.home-search-input .ant-input-affix-wrapper),
#homePage .search-bar:focus-within :deep(.home-search-input .ant-input) {
  background: var(--glass-bg-strong);
}

/* 搜索框聚焦发光双层光环 + 微放大 */
#homePage .search-bar :deep(.ant-input-wrapper) {
  position: relative;
  transition:
    box-shadow 0.3s ease,
    transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}

#homePage .search-bar :deep(.ant-input-wrapper-focused) {
  box-shadow:
    0 0 0 4px rgba(22, 119, 255, 0.12),
    0 0 20px rgba(22, 119, 255, 0.10),
    var(--app-shadow);
  transform: scale(1.02);
}

/* 搜索按钮 hover/active 微交互 */
#homePage .search-bar :deep(.ant-input-group-addon .ant-btn) {
  transition:
    background 0.2s ease,
    transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.2s ease;
}

#homePage .search-bar :deep(.ant-input-group-addon .ant-btn:hover) {
  transform: scale(1.05);
  box-shadow: 0 4px 12px rgba(22, 119, 255, 0.25);
}

#homePage .search-bar :deep(.ant-input-group-addon .ant-btn:active) {
  transform: scale(0.96);
}

/* 分类 Tabs 入场动画 */
#homePage .ant-tabs {
  animation: searchArea-enter 0.5s cubic-bezier(0.34, 1.56, 0.64, 1) both;
  animation-delay: 0.25s;
}

/* 标签栏入场动画 */
#homePage .tag-bar {
  animation: searchArea-enter 0.5s cubic-bezier(0.34, 1.56, 0.64, 1) both;
  animation-delay: 0.4s;
}

/* 骨架屏网格 */
.skeleton-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.skeleton-grid :deep(.skeleton-card) {
  flex: 1 1 calc(20% - 16px);
  min-width: 200px;
  max-width: 320px;
}

@media (max-width: 768px) {
  .skeleton-grid :deep(.skeleton-card) {
    flex: 1 1 calc(50% - 8px);
    min-width: 140px;
  }
}

@media (max-width: 480px) {
  .skeleton-grid :deep(.skeleton-card) {
    flex: 1 1 100%;
  }
}

/* 列表筛选切换过渡（增强版：先缩小淡出→放大淡入） */
.list-fade-enter-active,
.list-fade-leave-active {
  transition:
    opacity 0.3s ease,
    transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.list-fade-enter-from {
  opacity: 0;
  transform: scale(0.97) translateY(8px);
}
.list-fade-leave-to {
  opacity: 0;
  transform: scale(1.02) translateY(-4px);
}

/* 搜索区域入场动画 keyframe */
@keyframes searchArea-enter {
  from {
    opacity: 0;
    transform: translateY(-16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* "没有更多了"轻浮动 */
.no-more {
  display: inline-block;
  color: #999;
  font-size: 13px;
  animation: float-y 2.4s ease-in-out infinite;
}
</style>
