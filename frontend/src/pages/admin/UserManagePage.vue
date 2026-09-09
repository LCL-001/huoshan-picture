<template>
  <div id="userManagePage">
    <!-- 搜索表单 -->
    <a-form layout="inline" :model="searchParams" @finish="doSearch">
      <a-form-item label="账号">
        <a-input v-model:value="searchParams.userAccount" placeholder="输入账号" allow-clear />
      </a-form-item>
      <a-form-item label="用户名">
        <a-input v-model:value="searchParams.userName" placeholder="输入用户名" allow-clear />
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
        <template v-if="column.dataIndex === 'userAvatar'">
          <a-image :src="record.userAvatar" :width="120" />
        </template>
        <template v-else-if="column.dataIndex === 'userRole'">
          <div v-if="record.userRole === 'admin'">
            <a-tag color="green">管理员</a-tag>
          </div>
          <div v-else>
            <a-tag color="blue">普通用户</a-tag>
          </div>
        </template>
        <template v-if="column.dataIndex === 'createTime'">
          {{ formatDateTime(record.createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <div class="table-action-bar">
            <a-button class="table-action-button edit-action" @click="openUpdateModal(record)">
              <template #icon>
                <EditOutlined />
              </template>
              编辑
            </a-button>
            <a-button
              class="table-action-button delete-action"
              @click="doDelete(record.id)"
            >
              <template #icon>
                <DeleteOutlined />
              </template>
              删除
            </a-button>
          </div>
        </template>
      </template>
    </a-table>
    <a-modal
      v-model:visible="updateModalVisible"
      title="更新用户"
      :footer="false"
      width="560px"
      class="user-update-modal"
      @cancel="closeUpdateModal"
    >
      <div class="user-update-summary">
        <a-avatar :size="64" :src="updateForm.userAvatar" />
        <div class="user-update-title">
          <div class="user-update-name">{{ updateForm.userName || '未命名用户' }}</div>
          <div class="user-update-account">ID：{{ updateForm.id ?? '-' }}</div>
        </div>
      </div>
      <a-form
        name="userUpdateForm"
        layout="vertical"
        :model="updateForm"
        @finish="handleUpdateUser"
      >
        <a-form-item name="userName" label="用户名">
          <a-input v-model:value="updateForm.userName" placeholder="请输入用户名" allow-clear />
        </a-form-item>
        <a-form-item name="userAvatar" label="头像地址">
          <a-input v-model:value="updateForm.userAvatar" placeholder="请输入头像地址" allow-clear />
        </a-form-item>
        <a-form-item name="userProfile" label="简介">
          <a-textarea
            v-model:value="updateForm.userProfile"
            placeholder="请输入用户简介"
            :auto-size="{ minRows: 3, maxRows: 5 }"
            allow-clear
          />
        </a-form-item>
        <a-form-item name="userRole" label="用户角色">
          <a-select
            v-model:value="updateForm.userRole"
            placeholder="请选择用户角色"
            :options="userRoleOptions"
            allow-clear
          />
        </a-form-item>
        <a-form-item>
          <a-space class="user-update-actions">
            <a-button @click="closeUpdateModal">取消</a-button>
            <a-button type="primary" html-type="submit" :loading="updateLoading">保存</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>
<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  DeleteOutlined,
  EditOutlined,
} from '@ant-design/icons-vue'
import {
  deleteUserUsingPost,
  listUserVoByPageUsingPost,
  updateUserUsingPost,
} from '@/api/userController.ts'
import { message, Modal } from 'ant-design-vue'
import { formatDateTime } from '@/utils'

const columns = [
  {
    title: 'id',
    dataIndex: 'id',
  },
  {
    title: '账号',
    dataIndex: 'userAccount',
  },
  {
    title: '用户名',
    dataIndex: 'userName',
  },
  {
    title: '头像',
    dataIndex: 'userAvatar',
  },
  {
    title: '简介',
    dataIndex: 'userProfile',
  },
  {
    title: '用户角色',
    dataIndex: 'userRole',
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
  },
  {
    title: '操作',
    key: 'action',
    width: 136,
  },
]

// 定义数据
const dataList = ref<API.UserVO[]>([])
const total = ref(0)
const updateModalVisible = ref(false)
const updateLoading = ref(false)
const updateForm = reactive<API.UserUpdateRequest>({})
const userRoleOptions = [
  {
    label: '普通用户',
    value: 'user',
  },
  {
    label: '管理员',
    value: 'admin',
  },
]

// 搜索条件
const searchParams = reactive<API.UserQueryRequest>({
  current: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'ascend',
})

// 获取数据
const fetchData = async () => {
  const res = await listUserVoByPageUsingPost({
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

// 打开更新用户弹窗
const openUpdateModal = (record: API.UserVO) => {
  updateForm.id = record.id
  updateForm.userName = record.userName
  updateForm.userAvatar = record.userAvatar
  updateForm.userProfile = record.userProfile
  updateForm.userRole = record.userRole
  updateModalVisible.value = true
}

// 关闭更新用户弹窗
const closeUpdateModal = () => {
  updateModalVisible.value = false
}

// 更新用户
const handleUpdateUser = async () => {
  if (!updateForm.id) {
    return
  }
  updateLoading.value = true
  const res = await updateUserUsingPost({
    ...updateForm,
  })
  if (res.data.code === 0) {
    message.success('更新成功')
    closeUpdateModal()
    fetchData()
  } else {
    message.error('更新失败，' + res.data.message)
  }
  updateLoading.value = false
}

// 删除数据
const doDelete = (id: string) => {
  if (!id) return
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除该用户吗？此操作不可撤销。',
    okText: '确认删除',
    cancelText: '取消',
    okType: 'danger',
    onOk: async () => {
      const res = await deleteUserUsingPost({ id })
      if (res.data.code === 0) {
        message.success('删除成功')
        fetchData()
      } else {
        message.error('删除失败')
      }
    },
  })
}
</script>

<style scoped>
#userManagePage .table-action-bar {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

#userManagePage .table-action-button {
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

#userManagePage .table-action-button :deep(.anticon) {
  font-size: 14px;
}

#userManagePage .edit-action {
  color: #1677ff;
}

#userManagePage .edit-action:hover {
  background: #e6f4ff;
  color: #0958d9;
}

#userManagePage .delete-action {
  color: #d4380d;
}

#userManagePage .delete-action:hover {
  background: #fff1f0;
  color: #ad2102;
}

#userManagePage .user-update-summary {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
  padding: 18px;
  border-radius: 16px;
  background: var(--app-primary-softer);
}

#userManagePage .user-update-title {
  min-width: 0;
}

#userManagePage .user-update-name {
  color: #0f172a;
  font-size: 20px;
  font-weight: 700;
}

#userManagePage .user-update-account {
  margin-top: 6px;
  color: #64748b;
  font-size: 13px;
}

#userManagePage .user-update-actions {
  display: flex;
  justify-content: flex-end;
  width: 100%;
}
</style>
