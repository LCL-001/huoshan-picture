<template>
  <div class="url-picture-upload">
    <div class="url-input-group">
      <a-input
        v-model:value="fileUrl"
        class="url-input"
        size="large"
        placeholder="请输入图片地址，如 https://..."
        @pressEnter="handleUpload"
      />
      <a-button type="primary" size="large" class="url-submit" :loading="loading" @click="handleUpload">
        提交
      </a-button>
    </div>
    <!-- 预览区：未导入时显示引导占位 -->
    <div class="url-preview">
      <img v-if="picture?.url" :src="picture?.url" alt="avatar" />
      <div v-else class="url-preview-empty">
        <LinkOutlined class="url-preview-icon" />
        <p class="url-preview-text">粘贴图片直链，点击提交即可导入</p>
        <p class="url-preview-sub">支持 jpg / png，导入后可在右侧完善信息</p>
      </div>
    </div>
  </div>
</template>
<script lang="ts" setup>
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { LinkOutlined } from '@ant-design/icons-vue'
import { uploadPictureByUrlUsingPost } from '@/api/pictureController.ts'

interface Props {
  picture?: API.PictureVO
  spaceId?: number
  onSuccess?: (newPicture: API.PictureVO) => void
}

const props = defineProps<Props>()
const fileUrl = ref<string>()
const loading = ref<boolean>(false)

/**
 * 上传图片
 * @param file
 */
const handleUpload = async () => {
  loading.value = true
  try {
    const params: API.PictureUploadRequest = { fileUrl: fileUrl.value }
    params.spaceId = props.spaceId;
    if (props.picture) {
      params.id = props.picture.id
    }
    const res = await uploadPictureByUrlUsingPost(params)
    if (res.data.code === 0 && res.data.data) {
      message.success('图片上传成功')
      // 将上传成功的图片信息传递给父组件
      props.onSuccess?.(res.data.data)
    } else {
      message.error('图片上传失败，' + res.data.message)
    }
  } catch (error) {
    console.error('图片上传失败', error)
    message.error('图片上传失败，' + error.message)
  }
  loading.value = false
}
</script>
<style scoped>
.url-picture-upload {
  margin-bottom: 16px;
}

/* 输入行：与全站搜索框一致的分离式圆角按钮 */
.url-input-group {
  display: flex;
  gap: 10px;
}

.url-input {
  flex: 1;
  min-width: 0;
}

.url-submit {
  width: 110px;
  flex-shrink: 0;
}

/* 预览区：占位与图片共用容器 */
.url-preview {
  margin-top: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 280px;
}

.url-preview img {
  max-width: 100%;
  max-height: 480px;
  border-radius: var(--app-radius-md);
}

.url-preview-empty {
  width: 100%;
  min-height: 280px;
  border: 1px dashed var(--app-border);
  border-radius: var(--app-radius-md);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 20px;
  text-align: center;
}

.url-preview-icon {
  font-size: 36px;
  color: #8ec5ff;
  animation: float-y 2.4s ease-in-out infinite;
}

.url-preview-text {
  margin: 0;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.url-preview-sub {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
}

@media (max-width: 768px) {
  .url-preview,
  .url-preview-empty {
    min-height: 200px;
  }
}
</style>
