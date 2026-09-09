<template>
  <div id="spaceManagePage">
    <a-flex justify="space-between">
      <h2>空间管理</h2>
      <a-space>
        <a-button type="primary" href="/add_space">+ 创建空间</a-button>
        <a-button type="primary" ghost href="/space_analyze?queryPublic=1"
          >分析公共图库</a-button
        >
        <a-button type="primary" ghost href="/space_analyze?queryAll=1"
          >分析全部空间</a-button
        >
      </a-space>
    </a-flex>
    <div style="margin-bottom: 16px" />
    <!-- 搜索表单 -->
    <a-form layout="inline" :model="searchParams" @finish="doSearch">
      <a-form-item label="空间名称">
        <a-input v-model:value="searchParams.spaceName" placeholder="请输入空间名称" allow-clear />
      </a-form-item>
      <a-form-item name="spaceLevel" label="空间级别">
        <a-select
          v-model:value="searchParams.spaceLevel"
          style="min-width: 180px"
          placeholder="请选择空间级别"
          :options="SPACE_LEVEL_OPTIONS"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="空间类别" name="spaceType">
        <a-select
          v-model:value="searchParams.spaceType"
          :options="SPACE_TYPE_OPTIONS"
          placeholder="请输入空间类别"
          style="min-width: 180px"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="用户 id">
        <a-input v-model:value="searchParams.userId" placeholder="请输入用户 id" allow-clear />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit">搜索</a-button>
      </a-form-item>
    </a-form>
    <div style="margin-bottom: 16px" />
    <!-- 表格 -->
    <a-table
      :columns="columns"
      :data-source="dataList"
      :pagination="pagination"
      @change="doTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'spaceLevel'">
          <div>{{ SPACE_LEVEL_MAP[record.spaceLevel] }}</div>
        </template>
        <!-- 空间类别 -->
        <template v-if="column.dataIndex === 'spaceType'">
          <a-tag>{{ SPACE_TYPE_MAP[record.spaceType] }}</a-tag>
        </template>
        <template v-if="column.dataIndex === 'spaceUseInfo'">
          <div>大小：{{ formatSize(record.totalSize) }} / {{ formatSize(record.maxSize) }}</div>
          <div>数量：{{ record.totalCount }} / {{ record.maxCount }}</div>
        </template>
        <template v-if="column.dataIndex === 'createTime'">
          {{ dayjs(record.createTime).format('YYYY-MM-DD HH:mm:ss') }}
        </template>
        <template v-if="column.dataIndex === 'editTime'">
          {{ dayjs(record.editTime).format('YYYY-MM-DD HH:mm:ss') }}
        </template>
        <template v-else-if="column.key === 'action'">
          <div class="table-action-bar">
            <a-button class="table-action-button analyze-action" :href="`/space_analyze?spaceId=${record.id}`">
              <template #icon>
                <BarChartOutlined />
              </template>
              分析
            </a-button>
            <a-button class="table-action-button edit-action" :href="`/add_space?id=${record.id}`">
              <template #icon>
                <EditOutlined />
              </template>
              编辑
            </a-button>
            <a-button class="table-action-button delete-action" @click="doDelete(record.id)">
              <template #icon>
                <DeleteOutlined />
              </template>
              删除
            </a-button>
          </div>
        </template>
      </template>
    </a-table>
  </div>
</template>
<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { BarChartOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons-vue'
import { deleteSpaceUsingPost, listSpaceByPageUsingPost } from '@/api/spaceController.ts'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  SPACE_LEVEL_MAP,
  SPACE_LEVEL_OPTIONS,
  SPACE_TYPE_MAP,
  SPACE_TYPE_OPTIONS,
} from '../../constants/space.ts'
import { formatSize } from '../../utils'
import { SPACE_LIST_REFRESH_EVENT } from '@/constants/events.ts'
import { useTeamSpaceStore } from '@/stores/useTeamSpaceStore.ts'

const teamSpaceStore = useTeamSpaceStore()

const columns = [
  {
    title: 'id',
    dataIndex: 'id',
    width: 80,
  },
  {
    title: '空间名称',
    dataIndex: 'spaceName',
  },
  {
    title: '空间级别',
    dataIndex: 'spaceLevel',
  },
  {
    title: '空间类别',
    dataIndex: 'spaceType',
  },
  {
    title: '使用情况',
    dataIndex: 'spaceUseInfo',
  },
  {
    title: '用户 id',
    dataIndex: 'userId',
    width: 80,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
  },
  {
    title: '编辑时间',
    dataIndex: 'editTime',
  },
  {
    title: '操作',
    key: 'action',
    width: 236,
  },
]

// 定义数据
const dataList = ref<API.Space[]>([])
const total = ref(0)

// 搜索条件
const searchParams = reactive<API.SpaceQueryRequest>({
  current: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'descend',
})

// 获取数据
const fetchData = async () => {
  const res = await listSpaceByPageUsingPost({
    ...searchParams,
  })
  if (res.data.code === 0 && res.data.data) {
    dataList.value = res.data.data.records ?? []
    total.value = res.data.data.total ?? 0
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
}

// 页面加载时获取数据，请求一次
onMounted(() => {
  fetchData()
})

// 分页参数
const pagination = computed(() => {
  return {
    current: searchParams.current,
    pageSize: searchParams.pageSize,
    total: total.value,
    showSizeChanger: true,
    showTotal: (total) => `共 ${total} 条`,
  }
})

// 表格变化之后，重新获取数据
const doTableChange = (page: any) => {
  searchParams.current = page.current
  searchParams.pageSize = page.pageSize
  fetchData()
}

// 搜索数据
const doSearch = () => {
  // 重置页码
  searchParams.current = 1
  fetchData()
}

// 删除数据
const doDelete = (id: string) => {
  if (!id) return
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除这个空间吗？此操作不可撤销，空间内所有图片将被删除。',
    okText: '确认删除',
    cancelText: '取消',
    okType: 'danger',
    onOk: async () => {
      const res = await deleteSpaceUsingPost({ id })
      if (res.data.code === 0) {
        message.success('删除成功')
        teamSpaceStore.removeTeamSpace(id)
        fetchData()
        window.dispatchEvent(new CustomEvent(SPACE_LIST_REFRESH_EVENT, { detail: { spaceId: id } }))
      } else {
        message.error('删除失败')
      }
    },
  })
}
</script>

<style scoped>
#spaceManagePage .table-action-bar {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

#spaceManagePage .table-action-button {
  height: 28px;
  padding: 0 7px;
  border: none;
  border-radius: 7px;
  background: transparent;
  font-size: 13px;
  font-weight: 600;
  line-height: 28px;
  box-shadow: none;
  transition:
    background 0.2s ease,
    color 0.2s ease;
}

#spaceManagePage .table-action-button :deep(.anticon) {
  font-size: 14px;
}

#spaceManagePage .analyze-action {
  color: #0958d9;
}

#spaceManagePage .analyze-action:hover {
  background: #eef7ff;
  color: #003eb3;
}

#spaceManagePage .edit-action {
  color: #1677ff;
}

#spaceManagePage .edit-action:hover {
  background: #e6f4ff;
  color: #0958d9;
}

#spaceManagePage .delete-action {
  color: #d4380d;
}

#spaceManagePage .delete-action:hover {
  background: #fff1f0;
  color: #ad2102;
}
</style>
