<template>
  <div id="recycleBinPage">
    <a-flex justify="space-between" align="center" wrap="wrap" gap="8">
      <h2 style="margin: 0">回收站</h2>
      <a-space wrap>
        <a-button :disabled="!selectedRowKeys.length" :loading="restoring" @click="doRestore(selectedRowKeys)">
          恢复选中{{ selectedRowKeys.length ? `（${selectedRowKeys.length}）` : '' }}
        </a-button>
        <a-button
          danger
          :disabled="!selectedRowKeys.length"
          :loading="purging"
          @click="confirmPurge(selectedRowKeys)"
        >
          彻底删除选中{{ selectedRowKeys.length ? `（${selectedRowKeys.length}）` : '' }}
        </a-button>
        <a-button @click="fetchData">刷新</a-button>
      </a-space>
    </a-flex>

    <a-alert
      style="margin: 16px 0"
      type="info"
      show-icon
      message="删除的图片会先进回收站，保留 30 天；期间可随时恢复，到期后自动彻底删除（不可恢复）。"
    />

    <a-table
      :columns="columns"
      :data-source="dataList"
      :pagination="pagination"
      :loading="loading"
      row-key="id"
      :row-selection="{ selectedRowKeys, onChange: onSelectChange }"
      @change="doTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'url'">
          <a-image
            :src="record.thumbnailUrl || record.url"
            :width="72"
            :height="72"
            style="object-fit: cover; border-radius: 6px"
          />
        </template>
        <template v-else-if="column.dataIndex === 'spaceId'">
          {{ record.spaceId ? `空间 ${record.spaceId}` : '公共图库' }}
        </template>
        <template v-else-if="column.dataIndex === 'picSize'">
          {{ formatSize(record.picSize) }}
        </template>
        <template v-else-if="column.dataIndex === 'deleteTime'">
          {{ formatTime(record.deleteTime) }}
        </template>
        <template v-else-if="column.dataIndex === 'action'">
          <a-space>
            <a-button type="link" size="small" @click="doRestore([record.id])">恢复</a-button>
            <a-button type="link" size="small" danger @click="confirmPurge([record.id])">
              彻底删除
            </a-button>
          </a-space>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  listTrashUsingPost,
  purgePicturesUsingPost,
  restorePicturesUsingPost,
} from '@/api/pictureController'

interface TrashItem {
  id: string
  name?: string
  url?: string
  thumbnailUrl?: string
  spaceId?: string
  picSize?: number
  deleteTime?: string
}

interface TrashOpResult {
  pictureId?: string
  ok?: boolean
  message?: string
}

const dataList = ref<TrashItem[]>([])
const loading = ref(false)
const restoring = ref(false)
const purging = ref(false)
const selectedRowKeys = ref<string[]>([])

const columns = [
  { title: '预览', dataIndex: 'url', width: 100 },
  { title: '名称', dataIndex: 'name', ellipsis: true },
  { title: '所属', dataIndex: 'spaceId', width: 160 },
  { title: '大小', dataIndex: 'picSize', width: 100 },
  { title: '删除时间', dataIndex: 'deleteTime', width: 190 },
  { title: '操作', dataIndex: 'action', width: 170 },
]

const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showTotal: (total: number) => `共 ${total} 条`,
})

const onSelectChange = (keys: (string | number)[]) => {
  selectedRowKeys.value = keys.map(String)
}

const fetchData = async () => {
  loading.value = true
  try {
    const res = await listTrashUsingPost({
      current: pagination.current,
      pageSize: pagination.pageSize,
    })
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records ?? []
      pagination.total = Number(res.data.data.total ?? 0)
    } else {
      message.error('获取回收站失败：' + res.data.message)
    }
  } finally {
    loading.value = false
  }
}

const doTableChange = (pag: { current?: number; pageSize?: number }) => {
  pagination.current = pag.current ?? 1
  pagination.pageSize = pag.pageSize ?? 10
  fetchData()
}

/** 逐条结果里统计成功/失败，失败带原因提示（后端逐张复核权限与配额） */
const reportResults = (list: TrashOpResult[], successVerb: string) => {
  if (!Array.isArray(list)) return
  const failed = list.filter((item) => !item.ok)
  const okCount = list.length - failed.length
  if (okCount > 0) {
    message.success(`${successVerb} ${okCount} 张`)
  }
  if (failed.length > 0) {
    message.warning(`${failed.length} 张未成功：${failed[0].message || '请稍后重试'}`)
  }
}

const doRestore = async (ids: string[]) => {
  if (!ids.length) return
  restoring.value = true
  try {
    const res = await restorePicturesUsingPost({ pictureIdList: ids })
    if (res.data.code === 0) {
      reportResults(res.data.data, '已恢复')
      selectedRowKeys.value = []
      await fetchData()
    } else {
      message.error('恢复失败：' + res.data.message)
    }
  } finally {
    restoring.value = false
  }
}

const confirmPurge = (ids: string[]) => {
  if (!ids.length) return
  Modal.confirm({
    title: '彻底删除',
    content: `确定要彻底删除选中的 ${ids.length} 张图片吗？此操作不可恢复。`,
    okText: '彻底删除',
    cancelText: '取消',
    okType: 'danger',
    onOk: () => doPurge(ids),
  })
}

const doPurge = async (ids: string[]) => {
  purging.value = true
  try {
    const res = await purgePicturesUsingPost({ pictureIdList: ids })
    if (res.data.code === 0) {
      reportResults(res.data.data, '已彻底删除')
      selectedRowKeys.value = []
      await fetchData()
    } else {
      message.error('彻底删除失败：' + res.data.message)
    }
  } finally {
    purging.value = false
  }
}

const formatSize = (size?: number) => {
  if (!size && size !== 0) return '-'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(2)} MB`
}

const formatTime = (time?: string) => {
  if (!time) return '-'
  const date = new Date(time)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString()
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
#recycleBinPage h2 {
  color: #1e293b;
}
</style>
