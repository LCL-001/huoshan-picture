<template>
  <div id="spaceManagePage">
    <h2>空间成员管理</h2>
    <div style="margin-bottom: 16px" />
    <div v-if="loading" style="text-align: center; padding: 60px 0">
      <a-spin size="large" />
    </div>
    <a-result
      v-else-if="!hasPermission"
      status="403"
      title="无权访问"
      sub-title="你无权管理该空间的成员"
    />
    <template v-else>
      <a-form layout="inline" :model="formData" @finish="handleSubmit">
        <a-form-item label="用户 id" name="userId">
          <a-input v-model:value="formData.userId" placeholder="请输入用户 id" allow-clear />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit">添加用户</a-button>
        </a-form-item>
      </a-form>
      <div style="margin-bottom: 16px" />
      <a-table :columns="columns" :data-source="dataList">
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'userInfo'">
            <a-space>
              <a-avatar :src="record.user?.userAvatar" />
              {{ record.user?.userName }}
            </a-space>
          </template>
          <template v-if="column.dataIndex === 'spaceRole'">
            <a-select
              v-model:value="record.spaceRole"
              :options="SPACE_ROLE_OPTIONS"
              @change="(value) => editSpaceRole(value, record)"
            />
          </template>
          <template v-else-if="column.dataIndex === 'createTime'">
            {{ dayjs(record.createTime).format('YYYY-MM-DD HH:mm:ss') }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space wrap>
              <a-button type="link" danger @click="doDelete(record.id)">删除</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </template>
  </div>
</template>
<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { SPACE_PERMISSION_ENUM, SPACE_ROLE_OPTIONS } from '../../constants/space.ts'
import { getSpaceVoByIdUsingGet } from '@/api/spaceController.ts'
import {
  addSpaceUserUsingPost,
  deleteSpaceUserUsingPost,
  editSpaceUserUsingPost,
  listSpaceUserUsingPost,
} from '@/api/spaceUserController.ts'
import dayjs from 'dayjs'

interface Props {
  id: string
}

const props = defineProps<Props>()

const loading = ref(true)
const hasPermission = ref(false)

const columns = [
  {
    title: '用户',
    dataIndex: 'userInfo',
  },
  {
    title: '角色',
    dataIndex: 'spaceRole',
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
  },
  {
    title: '操作',
    key: 'action',
  },
]

const dataList = ref<API.SpaceUserVO[]>([])

const fetchData = async () => {
  const spaceId = props.id
  if (!spaceId) {
    return
  }
  const res = await listSpaceUserUsingPost({
    spaceId,
  })
  if (res.data.code === 0 && res.data.data) {
    dataList.value = res.data.data ?? []
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
}

const checkPermission = async () => {
  loading.value = true
  try {
    const res = await getSpaceVoByIdUsingGet({ id: props.id })
    if (res.data.code === 0 && res.data.data) {
      const permissionList = res.data.data.permissionList ?? []
      hasPermission.value = permissionList.includes(SPACE_PERMISSION_ENUM.SPACE_USER_MANAGE)
    }
  } catch {
    hasPermission.value = false
  } finally {
    loading.value = false
    if (hasPermission.value) {
      fetchData()
    }
  }
}

onMounted(() => {
  checkPermission()
})

const formData = reactive<API.SpaceUserAddRequest>({})

const handleSubmit = async () => {
  const spaceId = props.id
  if (!spaceId) {
    return
  }
  const res = await addSpaceUserUsingPost({
    spaceId,
    ...formData,
  })
  if (res.data.code === 0) {
    message.success('添加成功')
    fetchData()
  } else {
    message.error('添加失败，' + res.data.message)
  }
}

const editSpaceRole = async (value, record) => {
  const res = await editSpaceUserUsingPost({
    id: record.id,
    spaceRole: value,
  })
  if (res.data.code === 0) {
    message.success('修改成功')
  } else {
    message.error('修改失败，' + res.data.message)
  }
}

const doDelete = (id: string) => {
  if (!id) return
  Modal.confirm({
    title: '确认移除',
    content: '确定要移除该成员吗？',
    okText: '确认移除',
    cancelText: '取消',
    okType: 'danger',
    onOk: async () => {
      const res = await deleteSpaceUserUsingPost({ id })
      if (res.data.code === 0) {
        message.success('移除成功')
        fetchData()
      } else {
        message.error('移除失败')
      }
    },
  })
}
</script>
