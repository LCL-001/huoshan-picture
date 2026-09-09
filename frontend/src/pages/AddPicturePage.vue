<template>
  <div id="addPicturePage">
    <!-- 页头 -->
    <header class="page-header animate-fade-in-up">
      <h2 class="page-title">{{ route.query?.id ? '修改图片' : '上传图片' }}</h2>
      <p class="page-desc">上传图片并完善信息，即可{{ route.query?.id ? '保存修改' : '加入你的图库' }}</p>
      <div v-if="spaceId" class="space-chip animate-fade-in-up stagger-1">
        保存至空间：<a :href="`/space/${spaceId}`">{{ space?.spaceName ?? spaceId }}</a>
      </div>
    </header>

    <a-row :gutter="[20, 20]" class="content-row">
      <!-- 左列：上传与编辑 -->
      <a-col :xs="24" :md="13">
        <div class="panel-card animate-fade-in-up stagger-1">
          <a-tabs v-model:activeKey="uploadType">
            <a-tab-pane key="file" tab="文件上传">
              <!-- 图片上传组件 -->
              <PictureUpload :picture="picture" :spaceId="spaceId" :onSuccess="onSuccess" />
            </a-tab-pane>
            <a-tab-pane key="url" tab="URL 上传" force-render>
              <!-- URL 图片上传组件 -->
              <UrlPictureUpload :picture="picture" :spaceId="spaceId" :onSuccess="onSuccess" />
            </a-tab-pane>
          </a-tabs>
          <!-- 图片编辑 -->
          <div v-if="picture" class="edit-bar">
            <a-space size="middle">
              <a-button :icon="h(EditOutlined)" @click="doEditPicture">编辑图片</a-button>
              <a-button type="primary" :icon="h(FullscreenOutlined)" @click="doImagePainting">
                AI 扩图
              </a-button>
            </a-space>
            <ImageCropper
              ref="imageCropperRef"
              :imageUrl="picture?.url"
              :picture="picture"
              :spaceId="spaceId"
              :space="space"
              :onSuccess="onCropSuccess"
            />
            <ImageOutPainting
              ref="imageOutPaintingRef"
              :picture="picture"
              :spaceId="spaceId"
              :onSuccess="onImageOutPaintingSuccess"
            />
          </div>
        </div>
      </a-col>

      <!-- 右列：图片信息 -->
      <a-col :xs="24" :md="11">
        <div class="panel-card info-panel animate-fade-in-up stagger-2">
          <Transition name="fade-slide" mode="out-in">
            <!-- 未上传时的引导占位 -->
            <div v-if="!picture" key="empty" class="info-empty">
              <PictureOutlined class="info-empty-icon" />
              <p>上传图片后，在这里填写名称、简介等信息</p>
            </div>
            <!-- 图片信息表单 -->
            <a-form
              v-else
              key="form"
              name="pictureForm"
              layout="vertical"
              :model="pictureForm"
              @finish="handleSubmit"
            >
              <a-form-item name="name" label="名称">
                <a-input v-model:value="pictureForm.name" placeholder="请输入名称" allow-clear />
              </a-form-item>
              <a-form-item name="introduction" label="简介">
                <a-textarea
                  v-model:value="pictureForm.introduction"
                  placeholder="请输入简介"
                  :auto-size="{ minRows: 2, maxRows: 5 }"
                  allow-clear
                />
              </a-form-item>
              <a-form-item name="category" label="分类">
                <a-auto-complete
                  v-model:value="pictureForm.category"
                  placeholder="请输入分类"
                  :options="categoryOptions"
                  allow-clear
                />
              </a-form-item>
              <a-form-item name="tags" label="标签">
                <a-select
                  v-model:value="pictureForm.tags"
                  mode="tags"
                  placeholder="请输入标签"
                  :options="tagOptions"
                  allow-clear
                />
              </a-form-item>
              <a-form-item class="submit-item">
                <a-button type="primary" html-type="submit" class="submit-btn">
                  {{ route.query?.id ? '保存' : '创建' }}
                </a-button>
              </a-form-item>
            </a-form>
          </Transition>
        </div>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import PictureUpload from '@/components/PictureUpload.vue'
import { computed, h, onMounted, reactive, ref, watchEffect } from 'vue'
import { message } from 'ant-design-vue'
import {
  editPictureUsingPost,
  getPictureVoByIdUsingGet,
  listPictureTagCategoryUsingGet,
} from '@/api/pictureController.ts'
import { useRoute, useRouter } from 'vue-router'
import UrlPictureUpload from '@/components/UrlPictureUpload.vue'
import ImageCropper from '@/components/ImageCropper.vue'
import { EditOutlined, FullscreenOutlined, PictureOutlined } from '@ant-design/icons-vue'
import ImageOutPainting from '@/components/ImageOutPainting.vue'
import { getSpaceVoByIdUsingGet } from '@/api/spaceController.ts'

const router = useRouter()
const route = useRoute()

const picture = ref<API.PictureVO>()
const pictureForm = reactive<API.PictureEditRequest>({})
const uploadType = ref<'file' | 'url'>('file')
// 空间 id
const spaceId = computed(() => {
  return route.query?.spaceId
})

/**
 * 图片上传成功
 * @param newPicture
 */
const onSuccess = (newPicture: API.PictureVO) => {
  picture.value = newPicture
  pictureForm.name = newPicture.name
}

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: any) => {
  const pictureId = picture.value.id
  if (!pictureId) {
    return
  }
  const res = await editPictureUsingPost({
    id: pictureId,
    spaceId: spaceId.value,
    ...values,
  })
  // 操作成功
  if (res.data.code === 0 && res.data.data) {
    message.success('创建成功')
    // 跳转到图片详情页
    router.push({
      path: `/picture/${pictureId}`,
    })
  } else {
    message.error('创建失败，' + res.data.message)
  }
}

const categoryOptions = ref<string[]>([])
const tagOptions = ref<string[]>([])

/**
 * 获取标签和分类选项
 * @param values
 */
const getTagCategoryOptions = async () => {
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    tagOptions.value = (res.data.data.tagList ?? []).map((data: string) => {
      return {
        value: data,
        label: data,
      }
    })
    categoryOptions.value = (res.data.data.categoryList ?? []).map((data: string) => {
      return {
        value: data,
        label: data,
      }
    })
  } else {
    message.error('获取标签分类列表失败，' + res.data.message)
  }
}

onMounted(() => {
  getTagCategoryOptions()
})

// 获取老数据
const getOldPicture = async () => {
  // 获取到 id
  const id = route.query?.id
  if (id) {
    const res = await getPictureVoByIdUsingGet({
      id,
    })
    if (res.data.code === 0 && res.data.data) {
      const data = res.data.data
      picture.value = data
      pictureForm.name = data.name
      pictureForm.introduction = data.introduction
      pictureForm.category = data.category
      pictureForm.tags = data.tags
    }
  }
}

onMounted(() => {
  getOldPicture()
})

// ----- 图片编辑器引用 ------
const imageCropperRef = ref()

// 编辑图片
const doEditPicture = async () => {
  imageCropperRef.value?.openModal()
}

// 编辑成功事件
const onCropSuccess = (newPicture: API.PictureVO) => {
  picture.value = newPicture
}

// ----- AI 扩图引用 -----
const imageOutPaintingRef = ref()

// 打开 AI 扩图弹窗
const doImagePainting = async () => {
  imageOutPaintingRef.value?.openModal()
}

// AI 扩图保存事件
const onImageOutPaintingSuccess = (newPicture: API.PictureVO) => {
  picture.value = newPicture
}

// 获取空间信息
const space = ref<API.SpaceVO>()

// 获取空间信息
const fetchSpace = async () => {
  // 获取数据
  if (spaceId.value) {
    const res = await getSpaceVoByIdUsingGet({
      id: spaceId.value,
    })
    if (res.data.code === 0 && res.data.data) {
      space.value = res.data.data
    }
  }
}

watchEffect(() => {
  fetchSpace()
})
</script>

<style scoped>
/* ---------- 页头 ---------- */
.page-header {
  margin-bottom: 20px;
}

.page-title {
  position: relative;
  margin: 0 0 6px;
  padding-left: 14px;
  font-size: 22px;
  font-weight: 700;
}

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

.page-desc {
  margin: 0 0 10px 14px;
  font-size: 13px;
  color: var(--app-text-secondary);
}

/* 空间玻璃胶囊 */
.space-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 14px;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 13px;
  color: var(--app-text-secondary);
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  box-shadow: var(--glass-highlight);
}

.space-chip a {
  color: var(--app-primary);
  font-weight: 500;
}

/* ---------- 双栏面板 ---------- */
.content-row {
  margin-top: 4px;
}

.panel-card {
  padding: 18px 20px;
  border-radius: var(--app-radius-lg);
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  box-shadow: var(--app-shadow), var(--glass-highlight);
  height: 100%;
}

/* 编辑条：与上传区之间加分隔线 */
.edit-bar {
  text-align: center;
  margin-top: 4px;
  padding-top: 16px;
  border-top: 1px dashed var(--app-border);
}

/* ---------- 右列信息面板 ---------- */
.info-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.info-empty {
  min-height: 280px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border: 1px dashed var(--app-border);
  border-radius: var(--app-radius-md);
  color: var(--app-text-secondary);
  font-size: 13px;
  padding: 20px;
  text-align: center;
}

.info-empty-icon {
  font-size: 40px;
  color: #8ec5ff;
  animation: float-y 2.4s ease-in-out infinite;
}

.info-empty p {
  margin: 0;
}

.submit-item {
  margin-bottom: 0;
}

.submit-btn {
  width: 100%;
  height: 38px;
}

/* 表单出现/占位切换过渡 */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition:
    opacity 0.3s ease,
    transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* ---------- 移动端 ---------- */
@media (max-width: 768px) {
  .panel-card {
    padding: 14px;
  }

  .info-empty {
    min-height: 180px;
  }
}
</style>
