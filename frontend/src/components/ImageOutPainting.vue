<template>
  <a-modal
    class="image-out-painting"
    v-model:visible="visible"
    title="AI 扩图"
    :footer="false"
    @cancel="closeModal"
  >
    <a-alert
      v-if="statusLoading || !outPaintingAvailable"
      :type="statusLoading ? 'info' : 'warning'"
      show-icon
      :message="statusLoading ? '正在确认 AI 扩图状态…' : maintenanceMessage"
      style="margin-bottom: 16px"
    />
    <a-row gutter="16">
      <a-col span="12">
        <h4>原始图片</h4>
        <img :src="picture?.url" :alt="picture?.name" style="max-width: 100%" />
      </a-col>
      <a-col span="12">
        <h4>扩图结果</h4>
        <img
          v-if="resultImageUrl"
          :src="resultImageUrl"
          :alt="picture?.name"
          style="max-width: 100%"
        />
      </a-col>
    </a-row>
    <div style="margin-bottom: 16px" />
    <a-flex justify="center" gap="16">
      <a-button
        v-if="outPaintingAvailable"
        type="primary"
        :loading="!!taskId"
        ghost
        @click="createTask"
      >
        生成图片
      </a-button>
      <a-button v-if="resultImageUrl" type="primary" :loading="uploadLoading" @click="handleUpload">
        应用结果
      </a-button>
    </a-flex>
  </a-modal>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import {
  createPictureOutPaintingTaskUsingPost,
  getPictureOutPaintingStatusUsingGet,
  getPictureOutPaintingTaskUsingGet,
  uploadPictureByUrlUsingPost,
} from '@/api/pictureController.ts'
import { message } from 'ant-design-vue'

interface Props {
  picture?: API.PictureVO
  spaceId?: string
  onSuccess?: (newPicture: API.PictureVO) => void
}

const props = defineProps<Props>()

const resultImageUrl = ref<string>('')

// 任务 id
const taskId = ref<string>()

// Unknown status is not availability: only an explicit server true opens generation.
const outPaintingAvailable = ref(false)
const statusLoading = ref(false)
const maintenanceMessage = ref('')
const statusUnavailableMessage = '暂时无法确认 AI 扩图状态，请关闭后重试'
let statusRequestVersion = 0

const loadOutPaintingStatus = async () => {
  const version = ++statusRequestVersion
  outPaintingAvailable.value = false
  statusLoading.value = true
  maintenanceMessage.value = ''
  try {
    const res = await getPictureOutPaintingStatusUsingGet()
    if (version !== statusRequestVersion) return
    const status = res?.data?.code === 0 ? res.data.data : undefined
    outPaintingAvailable.value = status?.available === true
    maintenanceMessage.value =
      status?.available === false ? status.message || 'AI 扩图功能维护中' : statusUnavailableMessage
  } catch (error) {
    console.error('获取 AI 扩图维护状态失败', error)
    if (version === statusRequestVersion) {
      maintenanceMessage.value = statusUnavailableMessage
    }
  } finally {
    if (version === statusRequestVersion) statusLoading.value = false
  }
}

/**
 * 创建任务
 */
const createTask = async () => {
  if (!outPaintingAvailable.value || statusLoading.value || !props.picture?.id) {
    return
  }
  const res = await createPictureOutPaintingTaskUsingPost({
    pictureId: props.picture.id,
    // 根据需要设置扩图参数
    parameters: {
      xScale: 2,
      yScale: 2,
    },
  })
  if (res.data.code === 0 && res.data.data) {
    message.success('创建任务成功，请耐心等待，不要退出界面')
    const output = res.data.data.output
    taskId.value = output?.taskId
    // 开启轮询
    startPolling()
  } else {
    message.error('图片任务失败，' + res.data.message)
  }
}

// 轮询定时器
let pollingTimer: ReturnType<typeof setInterval> | null = null

// 开始轮询
const startPolling = () => {
  if (!taskId.value) {
    return
  }

  pollingTimer = setInterval(async () => {
    try {
      const res = await getPictureOutPaintingTaskUsingGet({
        taskId: taskId.value,
      })
      if (res.data.code === 0 && res.data.data) {
        const taskResult = res.data.data.output
        if (taskResult?.taskStatus === 'SUCCEEDED') {
          message.success('扩图任务执行成功')
          resultImageUrl.value = taskResult.outputImageUrl ?? ''
          // 清理轮询
          clearPolling()
        } else if (taskResult?.taskStatus === 'FAILED') {
          message.error('扩图任务执行失败')
          // 清理轮询
          clearPolling()
        }
      }
    } catch (error) {
      console.error('扩图任务轮询失败', error)
      message.error('扩图任务轮询失败，' + (error instanceof Error ? error.message : String(error)))
      // 清理轮询
      clearPolling()
    }
  }, 3000) // 每 3 秒轮询一次
}

// 清理轮询
const clearPolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
    taskId.value = undefined
  }
}

// 是否正在上传
const uploadLoading = ref(false)

/**
 * 上传图片
 * @param file
 */
const handleUpload = async () => {
  uploadLoading.value = true
  try {
    const params: API.PictureUploadRequest = {
      fileUrl: resultImageUrl.value,
      spaceId: props.spaceId,
    }
    if (props.picture) {
      params.id = props.picture.id
    }
    const res = await uploadPictureByUrlUsingPost(params)
    if (res.data.code === 0 && res.data.data) {
      message.success('图片上传成功')
      // 将上传成功的图片信息传递给父组件
      props.onSuccess?.(res.data.data)
      // 关闭弹窗
      closeModal()
    } else {
      message.error('图片上传失败，' + res.data.message)
    }
  } catch (error) {
    console.error('图片上传失败', error)
    message.error('图片上传失败，' + (error instanceof Error ? error.message : String(error)))
  }
  uploadLoading.value = false
}

// 是否可见
const visible = ref(false)

// 打开弹窗
const openModal = async () => {
  visible.value = true
  await loadOutPaintingStatus()
}

// 关闭弹窗
const closeModal = () => {
  statusRequestVersion++
  outPaintingAvailable.value = false
  statusLoading.value = false
  visible.value = false
}

// 暴露函数给父组件
defineExpose({
  openModal,
})
</script>

<style>
.image-out-painting {
  text-align: center;
}
</style>
