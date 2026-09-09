<template>
  <Transition name="detail-fade" mode="out-in">
    <div id="pictureDetailPage" class="detail-page">
    <!-- 返回上一级 -->
    <div class="detail-back">
      <a-button class="back-button" @click="doBack">
        <template #icon><ArrowLeftOutlined /></template>
        返回
      </a-button>
    </div>
    <a-row :gutter="[24, 24]" class="detail-row">
      <!-- 图片预览区 -->
      <a-col :xs="24" :md="14" :xl="16">
        <a-card class="preview-card" :body-style="{ padding: 0 }">
          <div class="preview-wrapper">
            <a-image
              :src="picture.url"
              :preview="{
                mask: false,
              }"
              style="max-height: 70vh; width: auto; display: block; margin: 0 auto; cursor: zoom-in"
              class="preview-image"
            />
          </div>
        </a-card>
      </a-col>

      <!-- 图片信息侧栏 -->
      <a-col :xs="24" :md="10" :xl="8">
        <div class="info-panel">
          <!-- 基本信息 -->
          <a-card class="info-card" :body-style="{ padding: '20px 24px' }">
            <template #title>
              <span class="info-card-title">基本信息</span>
            </template>
            <a-descriptions
              :column="1"
              size="middle"
              class="detail-descriptions"
            >
              <a-descriptions-item label="作者" :style="{ animationDelay: '0ms' }">
                <a-space class="author-info">
                  <a-avatar :size="28" :src="picture.user?.userAvatar" />
                  <span class="author-name">{{ picture.user?.userName ?? '未知' }}</span>
                </a-space>
              </a-descriptions-item>
              <a-descriptions-item label="名称" :style="{ animationDelay: '60ms' }">
                <span class="info-value-bold">{{ picture.name ?? '未命名' }}</span>
              </a-descriptions-item>
              <a-descriptions-item v-if="picture.introduction" label="简介" :style="{ animationDelay: '120ms' }">
                <span class="info-value-text">{{ picture.introduction }}</span>
              </a-descriptions-item>
              <a-descriptions-item label="分类" :style="{ animationDelay: '180ms' }">
                <a-tag v-if="picture.category" color="blue">{{ picture.category }}</a-tag>
                <span v-else class="info-value-text">默认</span>
              </a-descriptions-item>
              <a-descriptions-item label="标签" :style="{ animationDelay: '240ms' }">
                <a-space v-if="picture.tags?.length" wrap>
                  <a-tag v-for="tag in picture.tags" :key="tag" closable>{{ tag }}</a-tag>
                </a-space>
                <span v-else class="info-value-text">-</span>
              </a-descriptions-item>
              <a-descriptions-item label="格式" :style="{ animationDelay: '300ms' }">
                <span class="info-value-text">{{ picture.picFormat ?? '-' }}</span>
              </a-descriptions-item>
              <a-descriptions-item :label="picture.picWidth ? '尺寸' : '宽度'" :style="{ animationDelay: '360ms' }">
                <span v-if="picture.picWidth && picture.picHeight" class="info-value-text">
                  {{ picture.picWidth }} × {{ picture.picHeight }}
                </span>
                <span v-else class="info-value-text">{{ picture.picWidth ?? '-' }}</span>
              </a-descriptions-item>
              <a-descriptions-item label="大小" :style="{ animationDelay: '420ms' }">
                <span class="info-value-text">{{ formatSize(picture.picSize) }}</span>
              </a-descriptions-item>
              <a-descriptions-item label="主色调" :style="{ animationDelay: '480ms' }">
                <a-space v-if="picture.picColor">
                  <span class="info-value-text">{{ picture.picColor }}</span>
                  <div
                    class="color-swatch"
                    :style="{ backgroundColor: toHexColor(picture.picColor) }"
                  />
                </a-space>
                <span v-else class="info-value-text">-</span>
              </a-descriptions-item>
            </a-descriptions>
          </a-card>

          <!-- 操作按钮 -->
          <a-card class="action-card" :body-style="{ padding: '16px 24px' }">
            <a-space direction="vertical" style="width: 100%" :size="10">
              <a-button type="primary" size="large" block @click="doDownload" v-ripple>
                <template #icon><DownloadOutlined /></template>
                免费下载
              </a-button>
              <a-button size="large" block @click="doShare" v-ripple>
                <template #icon><ShareAltOutlined /></template>
                分享
              </a-button>
              <a-button
                v-if="canEdit"
                size="large"
                block
                @click="doEdit"
                v-ripple
              >
                <template #icon><EditOutlined /></template>
                编辑
              </a-button>
              <a-button
                v-if="canDelete"
                size="large"
                block
                danger
                @click="doDelete"
                v-ripple
              >
                <template #icon><DeleteOutlined /></template>
                删除
              </a-button>
            </a-space>
          </a-card>
        </div>
      </a-col>
    </a-row>
    <ShareModal ref="shareModalRef" :link="shareLink" />
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { deletePictureUsingPost, getPictureVoByIdUsingGet } from '@/api/pictureController.ts'
import { message, Modal } from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  ShareAltOutlined,
} from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { downloadImage, formatSize, toHexColor } from '@/utils'
import ShareModal from '@/components/ShareModal.vue'
import { SPACE_PERMISSION_ENUM } from '@/constants/space.ts'

interface Props {
  id: string | number
}

const props = defineProps<Props>()
const picture = ref<API.PictureVO>({})

// 通用权限检查函数
function createPermissionChecker(permission: string) {
  return computed(() => {
    return (picture.value.permissionList ?? []).includes(permission)
  })
}

// 定义权限检查
const canEdit = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_EDIT)
const canDelete = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_DELETE)

// 获取图片详情
const fetchPictureDetail = async () => {
  try {
    const res = await getPictureVoByIdUsingGet({
      id: props.id,
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

const router = useRouter()

// 返回上一级：从站内进入则回退历史，直接打开（分享链接）则回首页
const doBack = () => {
  if (window.history.state?.back) {
    router.back()
  } else {
    router.push('/')
  }
}

// 编辑
const doEdit = () => {
  router.push({
    path: '/add_picture',
    query: {
      id: picture.value.id,
      spaceId: picture.value.spaceId,
    },
  })
}

// 删除数据
const doDelete = () => {
  const id = picture.value.id
  if (!id) return
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除这张图片吗？此操作不可撤销。',
    okText: '确认删除',
    cancelText: '取消',
    okType: 'danger',
    onOk: async () => {
      const res = await deletePictureUsingPost({ id })
      if (res.data.code === 0) {
        message.success('删除成功')
      } else {
        message.error('删除失败')
      }
    },
  })
}

// 下载图片
const doDownload = () => {
  downloadImage(picture.value.url)
}

// ----- 分享操作 ----
const shareModalRef = ref()
// 分享链接
const shareLink = ref<string>()
// 分享
const doShare = () => {
  shareLink.value = `${window.location.protocol}//${window.location.host}/picture/${picture.value.id}`
  if (shareModalRef.value) {
    shareModalRef.value.openModal()
  }
}
</script>

<style scoped>
#pictureDetailPage {
  margin-bottom: 24px;
}

/* ========== 页面进入动画 ========== */
.detail-page {
  animation: detail-stagger-in 0.5s cubic-bezier(0.34, 1.56, 0.64, 1) both;
}

@keyframes detail-stagger-in {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 页面过渡 */
.detail-fade-enter-active {
  transition: opacity 0.35s ease;
}
.detail-fade-leave-active {
  transition: opacity 0.2s ease;
}
.detail-fade-enter-from,
.detail-fade-leave-to {
  opacity: 0;
}

/* ========== 栅格间距 ========== */
.detail-row {
  margin: 0 -12px;
}

/* ========== 返回按钮 ========== */
.detail-back {
  margin-bottom: 16px;
}

.back-button {
  border: 1px solid var(--glass-border);
  background: var(--glass-bg);
  color: var(--app-text-secondary);
  border-radius: 999px;
  transition:
    color 0.2s ease,
    border-color 0.2s ease,
    background 0.2s ease,
    box-shadow 0.2s ease;
}

.back-button:hover {
  color: #1677ff;
  border-color: #b7dcff;
  background: #f4f9ff;
  box-shadow: 0 8px 20px rgba(37, 99, 235, 0.1);
}

/* ========== 图片预览区 ========== */
.preview-card {
  border: none;
  box-shadow: var(--app-shadow);
  border-radius: var(--app-radius-lg);
  overflow: hidden;
  background: var(--app-surface);
}

.preview-wrapper {
  background: linear-gradient(120deg, #f8fafc 0%, #eef4ff 35%, #eefcff 70%, #f8fafc 100%);
  background-size: 300% 300%;
  animation: preview-flow 18s ease-in-out infinite;
  padding: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
  border-radius: var(--app-radius-lg);
}

/* 底衬缓慢流动 */
@keyframes preview-flow {
  0% {
    background-position: 0% 50%;
  }
  50% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0% 50%;
  }
}

.preview-image {
  border-radius: var(--app-radius-md);
  transition:
    transform 0.4s cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.4s ease;
}

.preview-image:hover {
  transform: scale(1.02);
  box-shadow: 0 14px 40px rgba(22, 119, 255, 0.16);
}

/* ========== 信息面板 ========== */
.info-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.info-card,
.action-card {
  border: none;
  box-shadow: var(--app-shadow);
  border-radius: var(--app-radius-lg);
  overflow: hidden;
  transition:
    transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.3s ease;
}

.info-card:hover,
.action-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--app-shadow-hover);
}

.info-card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text);
}

/* 描述项交错入场 */
.detail-descriptions :deep(.ant-descriptions-item) {
  animation: detail-item-in 0.4s cubic-bezier(0.34, 1.56, 0.64, 1) both;
  padding-bottom: 12px;
}

@keyframes detail-item-in {
  from {
    opacity: 0;
    transform: translateX(12px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

/* 描述标签样式 */
.detail-descriptions :deep(.ant-descriptions-item-label) {
  color: var(--app-text-secondary);
  font-weight: 500;
  font-size: 13px;
  padding-right: 12px;
}

.detail-descriptions :deep(.ant-descriptions-item-content) {
  color: var(--app-text);
}

/* 作者信息 */
.author-info {
  gap: 8px;
}

.author-info :deep(.ant-avatar) {
  transition: transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.author-info :deep(.ant-avatar:hover) {
  transform: scale(1.12) rotate(3deg);
}

.author-name {
  font-weight: 500;
  color: var(--app-text);
}

/* 值样式 */
.info-value-bold {
  font-weight: 600;
  color: var(--app-text);
}

.info-value-text {
  color: var(--app-text-secondary);
  font-size: 13px;
}

/* 颜色色块 */
.color-swatch {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.8);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.15);
  flex-shrink: 0;
  cursor: pointer;
  /* 等描述项滑入后再弹现 */
  animation: pop-in 0.45s cubic-bezier(0.34, 1.56, 0.64, 1) 0.5s both;
  transition:
    transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.25s ease;
}

.color-swatch:hover {
  transform: scale(1.25);
  box-shadow:
    0 4px 14px rgba(0, 0, 0, 0.22),
    0 0 0 4px rgba(22, 119, 255, 0.12);
}

/* 操作按钮 */
.action-card :deep(.ant-space-vertical .ant-btn) {
  transition:
    transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.2s ease;
}

.action-card :deep(.ant-space-vertical .ant-btn:hover) {
  transform: translateY(-2px);
}

/* 按钮 hover 图标弹跳 */
.action-card :deep(.ant-space-vertical .ant-btn .anticon) {
  transition: transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.action-card :deep(.ant-space-vertical .ant-btn:hover .anticon) {
  transform: translateY(-2px) scale(1.12);
}

/* 主按钮高光扫过 */
.action-card :deep(.ant-space-vertical .ant-btn-primary) {
  position: relative;
  overflow: hidden;
}

.action-card :deep(.ant-space-vertical .ant-btn-primary)::after {
  content: '';
  position: absolute;
  top: -20%;
  bottom: -20%;
  left: 0;
  width: 34%;
  background: linear-gradient(
    105deg,
    transparent 0%,
    rgba(255, 255, 255, 0.45) 50%,
    transparent 100%
  );
  transform: translateX(-130%) skewX(-18deg);
  pointer-events: none;
}

.action-card :deep(.ant-space-vertical .ant-btn-primary:not(:disabled):hover)::after {
  animation: shine-sweep 0.8s ease;
}

/* ========== 响应式 ========== */
@media (max-width: 768px) {
  .preview-wrapper {
    padding: 16px;
    min-height: 200px;
  }

  .info-card,
  .action-card {
    border-radius: var(--app-radius-md);
  }
}
</style>
