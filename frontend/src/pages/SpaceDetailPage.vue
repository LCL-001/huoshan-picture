<template>
  <div id="spaceDetailPage">
    <!-- 空间信息 -->
    <a-flex justify="space-between" :vertical="isMobile" :gap="8">
      <h2>{{ space.spaceName }}（{{ SPACE_TYPE_MAP[space.spaceType] }}）</h2>
      <a-space size="middle" :wrap="true">
        <a-button
          v-if="canUploadPicture"
          type="primary"
          :href="`/add_picture?spaceId=${id}`"
        >
          + 上传图片
        </a-button>
        <a-button
          v-if="canManageSpaceUser && space.spaceType === 1"
          type="primary"
          ghost
          :icon="h(TeamOutlined)"
          :href="`/spaceUserManage/${id}`"
        >
          成员管理
        </a-button>
        <a-button
          v-if="canManageSpaceUser"
          type="primary"
          ghost
          :icon="h(BarChartOutlined)"
          :href="`/space_analyze?spaceId=${id}`"
        >
          空间分析
        </a-button>
        <a-button
          v-if="canManageSpaceUser && space.spaceType === 1"
          danger
          ghost
          :icon="h(DeleteOutlined)"
          @click="doDeleteSpace"
        >
          删除空间
        </a-button>
        <a-button v-if="canEditPicture" :icon="h(EditOutlined)" @click="doBatchEdit"> 批量编辑</a-button>
        <a-tooltip
          :title="`占用空间 ${formatSize(space.totalSize)} / ${formatSize(space.maxSize)}`"
        >
          <a-progress
            type="circle"
            :size="42"
            :percent="((space.totalSize * 100) / space.maxSize).toFixed(1)"
          />
        </a-tooltip>
      </a-space>
    </a-flex>
    <div style="margin-bottom: 16px" />
    <!-- 搜索表单 -->
    <PictureSearchForm :onSearch="onSearch" />
    <div style="margin-bottom: 16px" />
    <!-- 按颜色搜索，跟其他搜索条件独立 -->
    <a-form-item label="按颜色搜索">
      <color-picker format="hex" @pureColorChange="onColorChange" />
    </a-form-item>
    <!-- 图片列表 -->
    <PictureList
      :dataList="dataList"
      :loading="loading"
      :showOp="true"
      :canEdit="canEditPicture"
      :canDelete="canDeletePicture"
      :onReload="fetchData"
      :animateCards="true"
    />
    <!-- 分页 -->
    <a-pagination
      style="text-align: right"
      v-model:current="searchParams.current"
      v-model:pageSize="searchParams.pageSize"
      :total="total"
      @change="onPageChange"
    />
    <BatchEditPictureModal
      ref="batchEditPictureModalRef"
      :spaceId="id"
      :pictureList="dataList"
      :onSuccess="onBatchEditPictureSuccess"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, h, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { deleteSpaceUsingPost, getSpaceVoByIdUsingGet } from '@/api/spaceController.ts'
import { message, Modal } from 'ant-design-vue'
import {
  listPictureVoByPageWithCacheUsingPost,
  searchPictureByColorUsingPost,
} from '@/api/pictureController.ts'
import { formatSize } from '@/utils'
import PictureList from '@/components/PictureList.vue'
import PictureSearchForm from '@/components/PictureSearchForm.vue'
import { ColorPicker } from 'vue3-colorpicker'
import 'vue3-colorpicker/style.css'
import BatchEditPictureModal from '@/components/BatchEditPictureModal.vue'
import { BarChartOutlined, DeleteOutlined, EditOutlined, TeamOutlined } from '@ant-design/icons-vue'
import { SPACE_PERMISSION_ENUM, SPACE_TYPE_MAP } from '../constants/space.ts'

interface Props {
  id: string | number
}

const props = defineProps<Props>()
const space = ref<API.SpaceVO>({})

const isMobile = ref(window.innerWidth < 768)
const onResize = () => { isMobile.value = window.innerWidth < 768 }
// KeepAlive 缓存下监听器跟激活状态绑定，缓存期间不响应事件
onMounted(() => window.addEventListener('resize', onResize))
onActivated(() => window.addEventListener('resize', onResize))
onDeactivated(() => window.removeEventListener('resize', onResize))
onUnmounted(() => window.removeEventListener('resize', onResize))

// 通用权限检查函数
function createPermissionChecker(permission: string) {
  return computed(() => {
    return (space.value.permissionList ?? []).includes(permission)
  })
}

// 定义权限检查
const canManageSpaceUser = createPermissionChecker(SPACE_PERMISSION_ENUM.SPACE_USER_MANAGE)
const canUploadPicture = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_UPLOAD)
const canEditPicture = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_EDIT)
const canDeletePicture = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_DELETE)

const router = useRouter()

const doDeleteSpace = () => {
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除该空间吗？此操作不可撤销，空间内所有图片将被删除。',
    okText: '确认删除',
    cancelText: '取消',
    okType: 'danger',
    onOk: async () => {
      const res = await deleteSpaceUsingPost({ id: props.id })
      if (res.data.code === 0) {
        message.success('删除成功')
        router.push('/my_space')
      } else {
        message.error('删除失败')
      }
    },
  })
}

// -------- 获取空间详情 --------
const fetchSpaceDetail = async () => {
  try {
    const res = await getSpaceVoByIdUsingGet({
      id: props.id,
    })
    if (res.data.code === 0 && res.data.data) {
      space.value = res.data.data
    } else {
      message.error('获取空间详情失败，' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取空间详情失败：' + e.message)
  }
}

onMounted(() => {
  fetchSpaceDetail()
})

// --------- 获取图片列表 --------

// KeepAlive 缓存标识（见 PageTransition 的 cachedPages）
defineOptions({ name: 'SpaceDetailPage' })

// 定义数据
const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const loading = ref(true)

// 搜索条件
const searchParams = ref<API.PictureQueryRequest>({
  current: 1,
  pageSize: 12,
  sortField: 'createTime',
  sortOrder: 'descend',
})

// 获取数据；silent 用于 KeepAlive 激活时的静默刷新
const fetchData = async (silent = false) => {
  if (!silent) {
    loading.value = true
  }
  // 转换搜索参数
  const params = {
    spaceId: props.id,
    ...searchParams.value,
  }
  const res = await listPictureVoByPageWithCacheUsingPost(params)
  if (res.data.code === 0 && res.data.data) {
    const records = res.data.data.records ?? []
    if (silent && dataList.value.length > 0) {
      // 静默刷新：仅把新出现的图片插入头部，保留当前列表状态
      const seen = new Set(dataList.value.map((p) => String(p.id)))
      const fresh = records.filter((p) => !seen.has(String(p.id)))
      if (fresh.length > 0) {
        dataList.value = [...fresh, ...dataList.value]
      }
    } else {
      dataList.value = records
    }
    total.value = res.data.data.total ?? 0
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
  loading.value = false
}

// 页面加载时获取数据，请求一次
onMounted(() => {
  fetchData()
})

// 从详情页返回时静默刷新数据（首次挂载已由 onMounted 请求过，跳过避免重复）
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  fetchData(true)
})

// 分页参数
const onPageChange = (page: number, pageSize: number) => {
  searchParams.value.current = page
  searchParams.value.pageSize = pageSize
  fetchData()
}

// 搜索
const onSearch = (newSearchParams: API.PictureQueryRequest) => {
  console.log('new', newSearchParams)

  searchParams.value = {
    ...searchParams.value,
    ...newSearchParams,
    current: 1,
  }
  console.log('searchparams', searchParams.value)
  fetchData()
}

// 按照颜色搜索
const onColorChange = async (color: string) => {
  loading.value = true
  const res = await searchPictureByColorUsingPost({
    picColor: color,
    spaceId: props.id,
  })
  if (res.data.code === 0 && res.data.data) {
    const data = res.data.data ?? []
    dataList.value = data
    total.value = data.length
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
  loading.value = false
}

// ---- 批量编辑图片 -----
const batchEditPictureModalRef = ref()

// 批量编辑图片成功
const onBatchEditPictureSuccess = () => {
  fetchData()
}

// 打开批量编辑图片弹窗
const doBatchEdit = () => {
  if (batchEditPictureModalRef.value) {
    batchEditPictureModalRef.value.openModal()
  }
}

// 空间 id 改变时，必须重新获取数据
watch(
  () => props.id,
  (newSpaceId) => {
    fetchSpaceDetail()
    fetchData()
  },
)
</script>

<style scoped>
#spaceDetailPage {
  margin-bottom: 16px;
}
</style>
