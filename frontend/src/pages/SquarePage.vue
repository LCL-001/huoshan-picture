<template>
  <div id="squarePage">
    <a-flex justify="space-between" style="margin-bottom: 16px">
      <a-radio-group v-model:value="feedMode" button-style="solid" @change="fetchData()">
        <a-radio-button value="all">全部</a-radio-button>
        <a-radio-button value="timeline">关注</a-radio-button>
      </a-radio-group>
      <a-button type="primary" @click="openEditor">+ 发布</a-button>
    </a-flex>

    <!-- 帖子瀑布流 -->
    <a-spin :spinning="loading">
      <div v-if="error && !loading" style="text-align: center; padding: 80px 0">
        <a-empty description="加载失败">
          <template #children>
            <a-button type="primary" @click="fetchData()">重新加载</a-button>
          </template>
        </a-empty>
      </div>
      <!-- 关注模式：用户列表 -->
      <div v-if="feedMode === 'timeline'" class="follow-user-grid">
        <a-card v-for="user in (dataList as any[])" :key="user.id" hoverable class="follow-user-card"
          @click="goDetail(undefined, user.id)">
          <a-flex align="center" gap="12">
            <a-avatar :size="48" :src="user.userAvatar" />
            <div style="flex: 1; min-width: 0">
              <div style="font-weight: 600; font-size: 15px">{{ user.userName }}</div>
              <div style="color: #999; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ user.userProfile || '这个人很懒，什么都没写~' }}</div>
            </div>
          </a-flex>
        </a-card>
      </div>
      <!-- 全部模式：帖子瀑布流 -->
      <div v-else class="masonry">
        <div v-for="(col, ci) in postColumns" :key="ci" class="masonry-column">
          <div v-for="post in col" :key="post.id" class="masonry-item">
            <a-card hoverable @click="goDetail(post.id)">
              <template #cover>
                <img
                  v-if="getFirstImage(post.images)"
                  :src="getFirstImage(post.images)"
                  loading="lazy"
                  style="width: 100%; display: block; aspect-ratio: 4 / 3; object-fit: cover"
                />
              </template>
              <a-card-meta :title="post.title">
                <template #description>
                  <div style="margin-bottom: 8px">{{ truncateContent(post.content, 100) }}</div>
                  <a-flex justify="space-between" align="center">
                    <a-space size="small">
                      <a-avatar :size="20" :src="post.user?.userAvatar" />
                      <span>{{ post.user?.userName }}</span>
                    </a-space>
                    <a-space size="small">
                      <span>{{ post.likeCount ?? 0 }} 赞</span>
                      <span>{{ post.commentCount ?? 0 }} 评论</span>
                    </a-space>
                  </a-flex>
                </template>
              </a-card-meta>
            </a-card>
          </div>
        </div>
      </div>
    </a-spin>

    <!-- 滚动加载 -->
    <div style="text-align: center; padding: 24px 0">
      <a-spin v-if="loadingMore" size="small" />
      <span v-else-if="!hasMore" style="color: #999; font-size: 13px">— 没有更多了 —</span>
    </div>

    <!-- 发布弹窗 -->
    <a-modal
      v-model:visible="editorVisible"
      title="发布帖子"
      :footer="false"
      width="640px"
      :destroy-on-close="true"
    >
      <a-form :model="formData" layout="vertical" @finish="handlePublish">
        <a-form-item label="标题" required>
          <a-input v-model:value="formData.title" placeholder="请输入标题" />
        </a-form-item>
        <a-form-item label="内容" required>
          <a-textarea
            v-model:value="formData.content"
            placeholder="分享你的想法..."
            :rows="6"
            :maxlength="2000"
            show-count
          />
        </a-form-item>
        <a-form-item label="图片">
          <a-space>
            <a-tooltip title="上传本地图片">
              <a-upload :custom-request="handleImageUpload" :show-upload-list="false" accept="image/*">
                <a-button><template #icon><UploadOutlined /></template>上传图片</a-button>
              </a-upload>
            </a-tooltip>
            <a-tooltip title="从我的图库选取">
              <a-button @click="openGalleryPicker"><template #icon><FolderOpenOutlined /></template>从图库选取</a-button>
            </a-tooltip>
          </a-space>
          <div v-if="uploadedImages.length > 0" style="margin-top: 8px; display: flex; gap: 8px; flex-wrap: wrap">
            <div v-for="(url, i) in uploadedImages" :key="i" style="position: relative">
              <img :src="url" style="width: 80px; height: 80px; object-fit: cover; border-radius: 4px" />
              <a-button size="small" danger type="link"
                style="position: absolute; top: -8px; right: -8px"
                @click="removeImage(i)">×</a-button>
            </div>
          </div>
        </a-form-item>
        <a-form-item label="标签（逗号分隔）">
          <a-input v-model:value="formData.tags" placeholder="例如：风景,美食,旅行" />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="publishing" block>发布</a-button>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 图库选取弹窗 -->
    <a-modal v-model:visible="galleryVisible" title="从图库选择" width="760px" :footer="false">
      <a-spin :spinning="galleryLoading">
        <div v-if="galleryList.length" style="display: flex; gap: 8px; flex-wrap: wrap; max-height: 400px; overflow-y: auto">
          <div v-for="pic in galleryList" :key="pic.id"
            style="width: 120px; cursor: pointer; border-radius: 4px; overflow: hidden; border: 2px solid transparent"
            :style="{ borderColor: selectedGalleryIds.has(pic.id!) ? '#1677ff' : 'transparent' }"
            @click="toggleGallerySelect(pic)">
            <img :src="pic.thumbnailUrl ?? pic.url" style="width: 100%; height: 100px; object-fit: cover" />
          </div>
        </div>
        <a-empty v-else description="暂无图片" />
      </a-spin>
      <a-flex justify="space-between" style="margin-top: 16px">
        <a-pagination v-model:current="galleryPage" :total="galleryTotal" :page-size="12" size="small"
          @change="loadGallery" simple />
        <a-button type="primary" @click="confirmGallerySelect">确认选择 ({{ selectedGalleryIds.size }})</a-button>
      </a-flex>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { FolderOpenOutlined, UploadOutlined } from '@ant-design/icons-vue'
import { listPostByPageUsingPost, publishPostUsingPost, listFollowingUsingGet } from '@/api/postController.ts'
import { uploadFileUsingPost } from '@/api/fileController.ts'
import { listPictureVoByPageWithCacheUsingPost } from '@/api/pictureController.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'

const router = useRouter()
const loginUserStore = useLoginUserStore()
const dataList = ref<API.PostVO[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref(false)
const feedMode = ref('all')

const winW = ref(window.innerWidth)
const colCount = computed(() => {
  if (winW.value <= 480) return 1
  if (winW.value <= 992) return 2
  if (winW.value <= 1200) return 3
  return 4
})
const postColumns = computed(() => {
  const cols: any[][] = Array.from({ length: colCount.value }, () => [])
  dataList.value.forEach((p, i) => cols[i % colCount.value].push(p))
  return cols
})


const searchParams = reactive({ current: 1, pageSize: 9 })
const loadingMore = ref(false)
const hasMore = ref(true)

const fetchData = async (append = false) => {
  if (!append) {
    loading.value = true
    searchParams.current = 1
  } else {
    loadingMore.value = true
  }
  try {
    let res
    if (feedMode.value === 'timeline') {
      const userRes = await listFollowingUsingGet()
      if (userRes.data.code === 0 && userRes.data.data) {
        dataList.value = userRes.data.data as any
        total.value = dataList.value.length
        hasMore.value = false
      }
    } else {
      res = await listPostByPageUsingPost({ ...searchParams })
      if (res.data.code === 0 && res.data.data) {
        const records = res.data.data.records ?? []
        if (append) {
          dataList.value.push(...records)
        } else {
          dataList.value = records
        }
        total.value = res.data.data.total ?? 0
        hasMore.value = dataList.value.length < total.value
      }
    }
    error.value = false
  } catch {
    if (!append) error.value = true
  } finally {
    loading.value = false
    loadingMore.value = false
  }
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
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', () => { winW.value = window.innerWidth })
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
})

const goDetail = (postId?: number, userId?: number) => {
  if (postId) router.push(`/post/${postId}`)
  else if (userId) router.push(`/user/${userId}`)
}

const getFirstImage = (images?: string) => {
  if (!images) return null
  return images.split(',')[0]
}

const truncateContent = (content?: string, max = 100) => {
  if (!content) return ''
  return content.length > max ? content.slice(0, max) + '...' : content
}

// 发布
const editorVisible = ref(false)
const publishing = ref(false)
const uploadedImages = ref<string[]>([])
const formData = reactive({ title: '', content: '', tags: '', images: '' })

const openEditor = () => {
  formData.title = ''
  formData.content = ''
  formData.tags = ''
  formData.images = ''
  uploadedImages.value = []
  editorVisible.value = true
}

const handleImageUpload = async ({ file }: any) => {
  try {
    const res = await uploadFileUsingPost(file)
    if (res.data.code === 0 && res.data.data) {
      uploadedImages.value.push(res.data.data)
    }
  } catch {
    message.error('图片上传失败')
  }
}

const removeImage = (i: number) => {
  uploadedImages.value.splice(i, 1)
}

const handlePublish = async () => {
  if (!formData.title || !formData.content) {
    message.warning('标题和内容不能为空')
    return
  }
  publishing.value = true
  try {
    formData.images = uploadedImages.value.join(',')
    const res = await publishPostUsingPost({ ...formData })
    if (res.data.code === 0) {
      message.success('发布成功')
      editorVisible.value = false
      searchParams.current = 1
      fetchData()
    } else {
      message.error('发布失败，' + res.data.message)
    }
  } finally {
    publishing.value = false
  }
}

// 图库选取
const galleryVisible = ref(false)
const galleryLoading = ref(false)
const galleryList = ref<API.PictureVO[]>([])
const galleryPage = ref(1)
const galleryTotal = ref(0)
const selectedGalleryIds = ref(new Set<number>())

const openGalleryPicker = () => {
  selectedGalleryIds.value = new Set()
  galleryPage.value = 1
  galleryVisible.value = true
  loadGallery()
}

const loadGallery = async () => {
  galleryLoading.value = true
  try {
    const res = await listPictureVoByPageWithCacheUsingPost({ current: galleryPage.value, pageSize: 12, userId: loginUserStore.loginUser.id })
    if (res.data.code === 0 && res.data.data) {
      galleryList.value = res.data.data.records ?? []
      galleryTotal.value = res.data.data.total ?? 0
    }
  } finally { galleryLoading.value = false }
}

const toggleGallerySelect = (pic: API.PictureVO) => {
  const id = pic.id!
  if (selectedGalleryIds.value.has(id)) selectedGalleryIds.value.delete(id)
  else selectedGalleryIds.value.add(id)
}

const confirmGallerySelect = () => {
  const urls = galleryList.value
    .filter(p => selectedGalleryIds.value.has(p.id!))
    .map(p => p.url!)
  uploadedImages.value.push(...urls)
  galleryVisible.value = false
}
</script>

<style scoped>
.follow-user-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.follow-user-card {
  cursor: pointer;
}

.masonry {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.masonry-column {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
