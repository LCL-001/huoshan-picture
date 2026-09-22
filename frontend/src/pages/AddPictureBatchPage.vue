<template>

  <div id="addPictureBatchPage">
    <h2 style="margin-bottom: 16px">批量上传</h2>
    <!-- 图片信息表单 -->
    <a-form name="formData" layout="vertical" :model="formData" @finish="handleSubmit">
      <a-form-item name="searchText" label="关键词">
        <a-input v-model:value="formData.searchText" placeholder="请输入关键词" allow-clear />
      </a-form-item>
      <a-form-item name="count" label="抓取数量">
        <a-input-number
          v-model:value="formData.count"
          placeholder="请输入数量"
          style="min-width: 180px"
          :min="1"
          :max="30"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="offset" label="偏移量">
        <a-input-number
          v-model:value="formData.offset"
          placeholder="请输入数量"
          style="min-width: 180px"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="namePrefix" label="名称前缀">
        <a-input
          v-model:value="formData.namePrefix"
          placeholder="请输入名称前缀，会自动补充序号"
          allow-clear
        />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit" style="width: 100%" :loading="loading">
          执行任务
        </a-button>
      </a-form-item>
    </a-form>

    <!-- 任务执行结果 -->
    <a-card
      v-if="taskResult"
      class="result-card animate-fade-in-up"
      :body-style="{ padding: '20px 24px' }"
    >
      <div class="result-head">
        <component :is="taskResult.icon" :class="['result-icon', taskResult.status]" />
        <div class="result-title-wrap">
          <div class="result-title">{{ taskResult.title }}</div>
          <div class="result-desc">{{ taskResult.desc }}</div>
        </div>
      </div>
      <div class="result-meta">
        <span>关键词：{{ formData.searchText || '-' }}</span>
        <span>抓取数量：{{ formData.count ?? '-' }}</span>
        <span>名称前缀：{{ formData.namePrefix || '-' }}</span>
        <span v-if="taskResult.elapsed">耗时：{{ taskResult.elapsed }}</span>
      </div>
      <a-space style="margin-top: 14px">
        <a-button type="primary" href="/admin/pictureManage">查看图片管理</a-button>
        <a-button href="/">返回首页</a-button>
      </a-space>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { markRaw, reactive, ref, shallowRef, type Component } from 'vue'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons-vue'
import { uploadPictureByBatchUsingPost } from '@/api/pictureController.ts'
import {
  mapBatchCrawlResponse,
  type BatchTaskStatus,
} from '@/utils/batchCrawlResult.ts'

const formData = reactive<API.PictureUploadByBatchRequest>({
  count: 10, offset: 0,
})
// 提交任务状态
const loading = ref(false)

// 任务执行结果：success=有新增；empty=完成但 0 张；background=请求超时但任务已转后台；error=失败
interface TaskResult {
  status: BatchTaskStatus | 'background'
  icon: Component
  title: string
  desc: string
  elapsed?: string
}
const taskResult = shallowRef<TaskResult | null>(null)

const STATUS_ICONS: Record<TaskResult['status'], Component> = {
  success: CheckCircleOutlined,
  empty: InfoCircleOutlined,
  error: CloseCircleOutlined,
  background: ClockCircleOutlined,
}

/**
 * 提交表单
 */
const handleSubmit = async () => {
  loading.value = true
  taskResult.value = null
  const startedAt = Date.now()
  const elapsedText = () => `${((Date.now() - startedAt) / 1000).toFixed(1)} 秒`
  try {
    // 后端同步爬取并批量入库，耗时随抓取数量增长，全局 10s 超时不够，单独放宽到 5 分钟
    const res = await uploadPictureByBatchUsingPost(
      {
        ...formData,
      },
      { timeout: 300000 },
    )
    // 响应 → 展示状态走纯函数：成功但 0 张是 empty 而不是失败（断言见 scripts/check-batch-crawl-result.mjs）
    const mapped = mapBatchCrawlResponse(res.data)
    taskResult.value = {
      status: mapped.status,
      icon: markRaw(STATUS_ICONS[mapped.status]),
      title: mapped.title,
      desc: mapped.desc,
      elapsed: elapsedText(),
    }
  } catch (e: any) {
    // 请求超时被中断时，后端任务仍在继续执行，避免误以为失败而重复提交
    const isTimeout = e?.code === 'ECONNABORTED' || /timeout/i.test(e?.message ?? '')
    taskResult.value = isTimeout
      ? {
          status: 'background',
          icon: markRaw(ClockCircleOutlined),
          title: '任务仍在后台执行',
          desc: '前端等待已超时中断，但后端任务不会停止；请勿重复提交，稍后可在图片管理页查看结果',
          elapsed: elapsedText(),
        }
      : {
          status: 'error',
          icon: markRaw(CloseCircleOutlined),
          title: '任务执行失败',
          desc: e?.message ?? '请稍后重试',
          elapsed: elapsedText(),
        }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
#addPictureBatchPage {
  max-width: 720px;
  margin: 0 auto;
}

/* 任务结果卡片：轻玻璃，不加 backdrop-filter */
.result-card {
  margin-top: 16px;
  border: 1px solid var(--glass-border);
  border-radius: var(--app-radius-md, 12px);
  background: var(--glass-bg);
}

.result-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.result-icon {
  font-size: 28px;
  line-height: 1;
}

.result-icon.success {
  color: #52c41a;
}

.result-icon.background {
  color: #faad14;
}

.result-icon.error {
  color: #ff4d4f;
}

/* 完成但没有新增（源地址都导入过了 / 搜索词没有可用图片）：中性提示，不是失败 */
.result-icon.empty {
  color: #1677ff;
}

.result-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--app-text, #0f172a);
}

.result-desc {
  margin-top: 2px;
  color: #64748b;
  font-size: 13px;
  line-height: 20px;
}

.result-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--glass-border, #e6f1fb);
  color: #94a3b8;
  font-size: 12px;
}
</style>
