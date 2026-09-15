<template>
  <a-modal
    v-model:visible="visible"
    title="AI 打标"
    :footer="false"
    :width="920"
    @cancel="closeModal"
  >
    <a-typography-paragraph type="secondary">
      * 服务端逐张看图给建议，只写标签与分类、不动审核状态；确认前不会写库。
    </a-typography-paragraph>
    <a-alert
      v-if="loading"
      type="info"
      show-icon
      message="正在逐张看图…"
      :description="`共 ${rows.length} 张，并行看图，通常 30~40 秒，请勿关闭弹窗。`"
      style="margin-bottom: 12px"
    />
    <a-alert
      v-else-if="failedCount"
      type="warning"
      show-icon
      :message="`${rows.length} 张里有 ${failedCount} 张没出结果`"
      description="失败的行不会被提交，可按需稍后重试；原因见每张的「状态」列。"
      style="margin-bottom: 12px"
    />
    <a-table
      :columns="columns"
      :data-source="rows"
      :pagination="false"
      row-key="pictureId"
      size="small"
      :scroll="{ y: 360 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'url'">
          <a-image :src="record.url" :width="72" />
        </template>
        <template v-if="column.dataIndex === 'tags'">
          <a-select
            v-model:value="record.tags"
            mode="tags"
            placeholder="建议标签"
            :options="tagOptions"
            :disabled="!record.ok"
            style="min-width: 220px"
          />
        </template>
        <template v-if="column.dataIndex === 'category'">
          <a-select
            v-model:value="record.category"
            placeholder="建议分类"
            :options="categoryOptions"
            :disabled="!record.ok"
            allow-clear
            style="min-width: 130px"
          />
        </template>
        <template v-if="column.dataIndex === 'status'">
          <a-tag v-if="record.ok" color="green">已出建议</a-tag>
          <span v-else class="ai-tag-modal__failed">{{ record.message || '没出结果' }}</span>
        </template>
      </template>
    </a-table>
    <div class="ai-tag-modal__footer">
      <a-space>
        <a-button @click="closeModal">取消</a-button>
        <a-button
          type="primary"
          :loading="submitting"
          :disabled="loading || !writableCount"
          @click="handleApply"
        >
          确认写入{{ writableCount ? `（${writableCount} 张）` : '' }}
        </a-button>
      </a-space>
    </div>
  </a-modal>
</template>
<script lang="ts" setup>
import { computed, ref } from 'vue'
import {
  applyAiTagsUsingPost,
  listPictureTagCategoryUsingGet,
  suggestAiTagsUsingPost,
} from '@/api/pictureController.ts'
import { message } from 'ant-design-vue'

/** 单次上限与后端 app.ai.vision.max-per-request 同口径；超过了先在页面上拦住，别白等一轮 */
const MAX_PER_REQUEST = 8
/** 看图是逐张调模型：单张 10~15s、并发 4，默认 axios 10s 超时必须放宽 */
const SUGGEST_TIMEOUT_MS = 180000
const APPLY_TIMEOUT_MS = 60000

interface Props {
  pictureIds: string[]
  onSuccess?: () => void
}

const props = withDefaults(defineProps<Props>(), {})

const visible = ref(false)
const loading = ref(false)
const submitting = ref(false)
const rows = ref<API.PictureAiTagSuggestionVO[]>([])
const tagOptions = ref<{ value: string; label: string }[]>([])
const categoryOptions = ref<{ value: string; label: string }[]>([])

const failedCount = computed(() => rows.value.filter((row) => !row.ok).length)

/** 可写入的行：出了建议且至少有一项内容（后端对空值不写） */
const writableRows = computed(() =>
  rows.value.filter(
    (row) => row.ok && ((row.tags?.length ?? 0) > 0 || (row.category ?? '').trim() !== ''),
  ),
)
const writableCount = computed(() => writableRows.value.length)

const columns = [
  { title: '图片', dataIndex: 'url', width: 90 },
  { title: '建议标签（可改）', dataIndex: 'tags' },
  { title: '分类（可改）', dataIndex: 'category', width: 150 },
  { title: '状态', dataIndex: 'status', width: 200 },
]

const openModal = async () => {
  const ids = props.pictureIds ?? []
  if (!ids.length) {
    message.warning('请先勾选要打标的图片')
    return
  }
  if (ids.length > MAX_PER_REQUEST) {
    message.error(`单次最多 ${MAX_PER_REQUEST} 张，当前选了 ${ids.length} 张，请分批`)
    return
  }
  visible.value = true
  rows.value = []
  await getTagCategoryOptions()
  await doSuggest(ids)
}

const closeModal = () => {
  visible.value = false
}

defineExpose({
  openModal,
})

const doSuggest = async (ids: string[]) => {
  loading.value = true
  try {
    const res = await suggestAiTagsUsingPost(
      { pictureIdList: ids },
      { timeout: SUGGEST_TIMEOUT_MS },
    )
    if (res.data.code === 0 && res.data.data) {
      rows.value = res.data.data
      if (!res.data.data.length) {
        message.warning('没有返回任何建议')
      }
    } else {
      message.error('看图失败：' + res.data.message)
    }
  } catch (e) {
    message.error('看图请求失败：' + errorText(e))
  } finally {
    loading.value = false
  }
}

const handleApply = async () => {
  const items = writableRows.value.map((row) => ({
    pictureId: row.pictureId,
    tags: row.tags ?? [],
    category: row.category ?? '',
  }))
  if (!items.length) {
    message.warning('没有可写入的标签或分类')
    return
  }
  submitting.value = true
  try {
    const res = await applyAiTagsUsingPost({ items }, { timeout: APPLY_TIMEOUT_MS })
    if (res.data.code === 0 && res.data.data) {
      const written = res.data.data.filter((item) => item.ok).length
      const failed = res.data.data.length - written
      message.success(failed ? `已写入 ${written} 张，${failed} 张失败` : `已写入 ${written} 张`)
      closeModal()
      props.onSuccess?.()
    } else {
      message.error('写入失败：' + res.data.message)
    }
  } catch (e) {
    message.error('写入请求失败：' + errorText(e))
  } finally {
    submitting.value = false
  }
}

/** axios 抛出的异常是 unknown：取一句能显示的文案，兜底给通用提示 */
const errorText = (e: unknown) => (e instanceof Error && e.message ? e.message : '请稍后重试')

/** 词表选项：让管理员改建议时优先从已有词里挑，与「批量编辑」同一套口径 */
const getTagCategoryOptions = async () => {
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    tagOptions.value = (res.data.data.tagList ?? []).map((data: string) => ({
      value: data,
      label: data,
    }))
    categoryOptions.value = (res.data.data.categoryList ?? []).map((data: string) => ({
      value: data,
      label: data,
    }))
  } else {
    message.error('获取标签分类列表失败，' + res.data.message)
  }
}
</script>

<style scoped>
.ai-tag-modal__failed {
  color: #c12e1f;
}

.ai-tag-modal__footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
