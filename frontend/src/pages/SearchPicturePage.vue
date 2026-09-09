<template>
  <div id="searchPicturePage">
    <!-- 页头 -->
    <header class="page-header animate-fade-in-up">
      <h2 class="page-title">以图搜图</h2>
      <p class="page-subtitle">基于原图内容检索相似图片</p>
    </header>

    <!-- 原图 -->
    <section class="origin-section animate-fade-in-up stagger-dyn" style="--stagger-i: 1">
      <h3 class="section-title">原图</h3>
      <a-card hoverable class="origin-card hover-shine">
        <template #cover>
          <img
            class="origin-img"
            :alt="picture.name"
            :src="picture.thumbnailUrl ?? picture.url"
            @load="($event.target as HTMLElement).classList.add('loaded')"
          />
        </template>
      </a-card>
    </section>

    <!-- 识图结果 -->
    <section class="result-section animate-fade-in-up stagger-dyn" style="--stagger-i: 2">
      <h3 class="section-title">识图结果</h3>
      <!-- 加载骨架屏 -->
      <div v-if="loading && !dataList.length" class="skeleton-grid">
        <div v-for="i in 10" :key="i" class="skeleton-item animate-fade-in-up stagger-dyn" :style="{ '--stagger-i': i - 1 }">
          <SkeletonCard />
        </div>
      </div>
      <!-- 图片结果列表 -->
      <a-list
        v-else
        :grid="{ gutter: 16, xs: 1, sm: 2, md: 3, lg: 4, xl: 5, xxl: 6 }"
        :data-source="dataList"
      >
        <template #renderItem="{ item: picture, index }">
          <a-list-item
            class="result-item animate-fade-in-up stagger-dyn"
            :style="{ '--stagger-i': Math.min(index ?? 0, 12) }"
          >
            <a :href="picture.fromUrl" target="_blank" rel="noopener">
              <!-- 单张图片 -->
              <a-card hoverable class="result-card hover-shine">
                <template #cover>
                  <img
                    :alt="picture.name"
                    :src="picture.thumbUrl"
                    loading="lazy"
                    class="result-img"
                    @load="($event.target as HTMLElement).classList.add('loaded')"
                  />
                </template>
              </a-card>
            </a>
          </a-list-item>
        </template>
      </a-list>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  getPictureVoByIdUsingGet,
  searchPictureByPictureUsingPost,
} from '@/api/pictureController.ts'
import { message } from 'ant-design-vue'
import { useRoute } from 'vue-router'
import SkeletonCard from '@/components/SkeletonCard.vue'

const route = useRoute()

const pictureId = computed(() => {
  return route.query?.pictureId
})
const picture = ref<API.PictureVO>({})

// 获取图片详情
const fetchPictureDetail = async () => {
  try {
    const res = await getPictureVoByIdUsingGet({
      id: pictureId.value,
    })
    if (res.data.code === 0 && res.data.data) {
      picture.value = res.data.data
    } else {
      message.error('获取图片详情失败，' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取图片详情失败：' + e.message)
  }
}

onMounted(() => {
  fetchPictureDetail()
})

// 以图搜图结果
const dataList = ref<API.ImageSearchResult[]>([])
const loading = ref<boolean>(true)
// 获取搜图结果
const fetchResultData = async () => {
  loading.value = true;
  try {
    const res = await searchPictureByPictureUsingPost({
      pictureId: pictureId.value,
    })
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data ?? []
    } else {
      message.error('获取数据失败，' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取数据失败：' + e.message)
  }
  loading.value = false;
}

// 页面加载时请求一次
onMounted(() => {
  fetchResultData()
})
</script>

<style scoped>
#searchPicturePage {
  margin-bottom: 16px;
}

/* ---------- 页头 ---------- */

.page-header {
  margin-bottom: 8px;
}

.page-title {
  position: relative;
  margin: 0;
  padding-left: 14px;
  font-size: 22px;
  font-weight: 700;
}

/* 标题主色装饰条 */
.page-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 5px;
  height: 22px;
  border-radius: 3px;
  background: var(--app-primary);
}

.page-subtitle {
  margin: 6px 0 0 14px;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.section-title {
  position: relative;
  margin: 0 0 14px;
  padding-left: 12px;
  font-size: 16px;
  font-weight: 600;
}

.section-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 16px;
  border-radius: 2px;
  background: var(--app-primary);
}

/* ---------- 原图卡片 ---------- */

.origin-section {
  margin: 20px 0 28px;
}

.origin-card {
  position: relative;
  width: 240px;
  border-radius: var(--app-radius-lg);
  overflow: hidden;
  transition:
    transform 0.35s cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.35s ease;
}

.origin-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--app-shadow-hover);
}

.origin-card :deep(.ant-card-cover) {
  overflow: hidden;
}

.origin-img {
  display: block;
  width: 100%;
  height: 180px;
  object-fit: cover;
  opacity: 0;
  transition:
    transform 0.45s cubic-bezier(0.34, 1.56, 0.64, 1),
    opacity 0.4s ease;
}

.origin-img.loaded {
  opacity: 1;
}

.origin-card:hover .origin-img {
  transform: scale(1.06);
}

/* ---------- 结果列表 ---------- */

.result-item.ant-list-item {
  padding: 0;
}

.result-card {
  position: relative;
  border-radius: var(--app-radius-md);
  overflow: hidden;
  transition:
    transform 0.35s cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.35s ease;
}

.result-card:hover {
  transform: translateY(-6px);
  box-shadow:
    var(--app-shadow-hover),
    0 0 0 1px rgba(22, 119, 255, 0.08);
}

.result-card :deep(.ant-card-cover) {
  overflow: hidden;
}

.result-img {
  display: block;
  width: 100%;
  height: 180px;
  object-fit: cover;
  opacity: 0;
  transition:
    transform 0.4s cubic-bezier(0.34, 1.56, 0.64, 1),
    opacity 0.4s ease;
}

.result-img.loaded {
  opacity: 1;
}

.result-card:hover .result-img {
  transform: scale(1.06);
}

/* ---------- 骨架屏 ---------- */

.skeleton-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.skeleton-item {
  flex: 1 1 180px;
  min-width: 160px;
  max-width: 260px;
}

@media (max-width: 768px) {
  .skeleton-item {
    flex: 1 1 100%;
    max-width: 100%;
  }
}
</style>
