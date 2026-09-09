<template>
  <div class="picture-list">
    <!-- 图片瀑布流 -->
    <a-spin :spinning="loading">
      <div class="masonry">
        <div v-for="(column, ci) in columns" :key="ci" class="masonry-column">
          <div v-for="(picture, pi) in column" :key="picture.id" class="masonry-item"
            :class="{
              'animate-fade-in-up': animateCards,
              ['stagger-' + ((ci * 3 + pi) % 10)]: animateCards
            }"
            @mousemove="onTiltMove"
            @mouseleave="onTiltLeave"
          >
            <a-card hoverable @click="doClickPicture(picture)" class="picture-card">
              <template #cover>
                <div class="cover-wrapper">
                  <img
                    :alt="picture.name"
                    :src="picture.thumbnailUrl ?? picture.url"
                    loading="lazy"
                    @load="($event.target as HTMLElement).classList.add('loaded')"
                    :style="{
                      width: '100%',
                      display: 'block',
                      aspectRatio: picture.picWidth && picture.picHeight ? `${picture.picWidth} / ${picture.picHeight}` : '4 / 3',
                      objectFit: 'cover',
                    }"
                  />
                  <div class="cover-overlay" :class="{ revealed: revealedPictureId === picture.id }">
                    <div class="cover-title">{{ picture.name }}</div>
                    <a-flex>
                      <a-tag color="green">{{ picture.category ?? '默认' }}</a-tag>
                      <a-tag v-for="tag in picture.tags" :key="tag">{{ tag }}</a-tag>
                    </a-flex>
                  </div>
                </div>
              </template>
              <template v-if="showOp" #actions>
                <ShareAltOutlined @click="(e) => doShare(picture, e)" />
                <SearchOutlined @click="(e) => doSearch(picture, e)" />
                <EditOutlined v-if="canEdit" @click="(e) => doEdit(picture, e)" />
                <DeleteOutlined v-if="canDelete" @click="(e) => doDelete(picture, e)" />
              </template>
            </a-card>
          </div>
        </div>
      </div>
    </a-spin>
    <ShareModal ref="shareModalRef" :link="shareLink" />
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import {
  DeleteOutlined,
  EditOutlined,
  SearchOutlined,
  ShareAltOutlined,
} from '@ant-design/icons-vue'
import { deletePictureUsingPost } from '@/api/pictureController.ts'
import { message, Modal } from 'ant-design-vue'
import ShareModal from '@/components/ShareModal.vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

interface Props {
  dataList?: API.PictureVO[]
  loading?: boolean
  showOp?: boolean
  canEdit?: boolean
  canDelete?: boolean
  onReload?: () => void
  /** 是否启用卡片交错入场动画 */
  animateCards?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  dataList: () => [],
  loading: false,
  showOp: false,
  canEdit: false,
  canDelete: false,
  animateCards: false,
})

const winWidth = ref(window.innerWidth)
const onResize = () => { winWidth.value = window.innerWidth }
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const columnCount = computed(() => {
  // 移动端保留 2 列：单列图片过大、滚动冗长，双列更紧凑
  if (winWidth.value <= 480) return 2
  if (winWidth.value <= 768) return 2
  if (winWidth.value <= 992) return 3
  if (winWidth.value <= 1200) return 4
  return 5
})

const columns = computed(() => {
  const colCount = columnCount.value
  const cols: API.PictureVO[][] = Array.from({ length: colCount }, () => [])
  // 最短列优先：按图片宽高比估算各列累计高度，保证各列底部尽量齐平
  const colHeights = new Array(colCount).fill(0)
  props.dataList?.forEach((pic) => {
    let shortest = 0
    for (let i = 1; i < colCount; i++) {
      if (colHeights[i] < colHeights[shortest]) shortest = i
    }
    cols[shortest].push(pic)
    // 缺失宽高时按 4/3 占位比例兜底（与封面 aspectRatio 样式一致）；
    // 追加约一个列内间距（16px/列宽 ≈ 0.08），避免小图多的列因间距累计被低估
    const ratio =
      (pic.picWidth && pic.picHeight
        ? Number(pic.picHeight) / Number(pic.picWidth)
        : 3 / 4) + 0.08
    colHeights[shortest] += ratio
  })
  return cols
})

// ----- 3D 倾斜跟随（仅精确指针且未开启"减弱动态效果"时启用） -----
// 倾斜变量挂在 .masonry-item 上由子级卡片读取，避免与卡片入场动画的 fill 冲突
const canTilt =
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(pointer: fine)').matches &&
  !window.matchMedia('(prefers-reduced-motion: reduce)').matches
const MAX_TILT = 5

const onTiltMove = (e: MouseEvent) => {
  if (!canTilt) return
  const el = e.currentTarget as HTMLElement
  const rect = el.getBoundingClientRect()
  const px = (e.clientX - rect.left) / rect.width - 0.5
  const py = (e.clientY - rect.top) / rect.height - 0.5
  el.style.setProperty('--tilt-rx', `${(-py * MAX_TILT).toFixed(2)}deg`)
  el.style.setProperty('--tilt-ry', `${(px * MAX_TILT).toFixed(2)}deg`)
}

const onTiltLeave = (e: MouseEvent) => {
  const el = e.currentTarget as HTMLElement
  el.style.setProperty('--tilt-rx', '0deg')
  el.style.setProperty('--tilt-ry', '0deg')
}

const router = useRouter()
// 触屏设备无 hover：第一次点按先显示标题/标签遮罩，再点一次才进入详情（模拟桌面 hover）
const isTouchDevice =
  typeof window.matchMedia === 'function' && window.matchMedia('(hover: none)').matches
const revealedPictureId = ref<API.PictureVO['id'] | null>(null)
// 跳转至图片详情页
const doClickPicture = (picture: API.PictureVO) => {
  if (isTouchDevice && revealedPictureId.value !== picture.id) {
    revealedPictureId.value = picture.id
    return
  }
  revealedPictureId.value = null
  router.push({
    path: `/picture/${picture.id}`,
  })
}

// 搜索
const doSearch = (picture, e) => {
  // 阻止冒泡
  e.stopPropagation()
  // 当前窗口进入以图搜图页面
  router.push({
    path: '/search_picture',
    query: {
      pictureId: picture.id,
    },
  })
}

// 编辑
const doEdit = (picture, e) => {
  // 阻止冒泡
  e.stopPropagation()
  // 跳转时一定要携带 spaceId
  router.push({
    path: '/add_picture',
    query: {
      id: picture.id,
      spaceId: picture.spaceId,
    },
  })
}

// 删除数据
const doDelete = (picture, e) => {
  e.stopPropagation()
  const id = picture.id
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
        props.onReload?.()
      } else {
        message.error('删除失败')
      }
    },
  })
}

// ----- 分享操作 ----
const shareModalRef = ref()
// 分享链接
const shareLink = ref<string>()
// 分享
const doShare = (picture, e) => {
  // 阻止冒泡
  e.stopPropagation()
  shareLink.value = `${window.location.protocol}//${window.location.host}/picture/${picture.id}`
  if (shareModalRef.value) {
    shareModalRef.value.openModal()
  }
}
</script>

<style scoped>
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

.masonry-item {
  /* item fills its column */
}

.cover-wrapper {
  position: relative;
  overflow: hidden;
}

.cover-wrapper img {
  transition:
    transform 0.4s cubic-bezier(0.34, 1.56, 0.64, 1),
    opacity 0.4s ease;
  opacity: 0;
}

.cover-wrapper img.loaded {
  opacity: 1;
}

.picture-card {
  border-radius: var(--app-radius-md, 12px);
  overflow: hidden;
  /* 倾斜变量由父级 .masonry-item 的 mousemove 写入（见 script），无指针时保持 0 */
  transform: perspective(900px) rotateX(var(--tilt-rx, 0deg)) rotateY(var(--tilt-ry, 0deg));
  transition:
    transform 0.25s ease-out,
    box-shadow 0.35s ease;
  position: relative;
}

/* 卡片顶部光线 */
.picture-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(22, 119, 255, 0.5), transparent);
  opacity: 0;
  transition: opacity 0.35s ease;
  z-index: 2;
  pointer-events: none;
}

/* 卡片右下角光晕 */
.picture-card::after {
  content: '';
  position: absolute;
  bottom: -20px;
  right: -20px;
  width: 60px;
  height: 60px;
  background: radial-gradient(
    circle,
    rgba(22, 119, 255, 0.08) 0%,
    transparent 70%
  );
  border-radius: 50%;
  opacity: 0;
  transition: opacity 0.4s ease;
  pointer-events: none;
  z-index: -1;
}

.picture-card:hover {
  transform: perspective(900px) translateY(-6px) scale(1.02)
    rotateX(var(--tilt-rx, 0deg)) rotateY(var(--tilt-ry, 0deg));
  box-shadow:
    var(--app-shadow-hover, 0 16px 42px rgba(37, 99, 235, 0.14)),
    0 0 0 1px rgba(22, 119, 255, 0.08);
}

.picture-card:hover::before {
  opacity: 1;
}

.picture-card:hover::after {
  opacity: 1;
}

/* 封面图 hover 缓慢放大 */
.picture-card:hover .cover-wrapper img {
  transform: scale(1.05);
}

/* 封面对角高光扫过 */
.cover-wrapper::after {
  content: '';
  position: absolute;
  top: -20%;
  bottom: -20%;
  left: 0;
  width: 34%;
  background: linear-gradient(
    105deg,
    transparent 0%,
    rgba(255, 255, 255, 0.32) 50%,
    transparent 100%
  );
  transform: translateX(-130%) skewX(-18deg);
  pointer-events: none;
  z-index: 1;
}

.picture-card:hover .cover-wrapper::after {
  animation: shine-sweep 0.9s ease;
}

/* 遮罩层：从下方滑入 + 渐变加深 */
.cover-overlay {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 32px 14px 10px;
  background: linear-gradient(to top, rgba(0, 0, 0, 0.7) 0%, rgba(0, 0, 0, 0.3) 60%, transparent 100%);
  opacity: 0;
  transform: translateY(8px);
  transition:
    opacity 0.3s ease,
    transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
  z-index: 2;
}

.picture-card:hover .cover-overlay {
  opacity: 1;
  transform: translateY(0);
}

.cover-title {
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 操作图标 hover 弹跳 */
.picture-card :deep(.ant-card-actions > li .anticon) {
  transition: transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.picture-card :deep(.ant-card-actions > li:hover .anticon) {
  transform: translateY(-3px) scale(1.18);
}

/* 触屏设备无 hover：点按卡片后显示信息遮罩（与桌面 hover 对齐） */
@media (hover: none) {
  .cover-overlay.revealed {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
