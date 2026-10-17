<template>
  <div id="spaceDetailPage">
    <!-- 空间信息 -->
    <a-flex justify="space-between" :vertical="isMobile" :gap="8">
      <h2>{{ space.spaceName }}（{{ SPACE_TYPE_MAP[space.spaceType ?? 0] }}）</h2>
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
            :percent="((Number(space.totalSize ?? 0) * 100) / Number(space.maxSize ?? 0)).toFixed(1)"
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
      :key="`pictures-${privateContentVersion}`"
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
      :key="`batch-edit-${privateContentVersion}`"
      ref="batchEditPictureModalRef"
      :spaceId="spaceId"
      :pictureList="dataList"
      :onSuccess="onBatchEditPictureSuccess"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, h, onActivated, onBeforeUnmount, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
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
  id: string
}

const props = defineProps<Props>()
// 路由参数本身就是字符串，直接沿用；不可 Number()——19 位雪花 id 超出 JS 安全整数会丢精度
const spaceId = computed(() => props.id)
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
      const res = await deleteSpaceUsingPost({ id: spaceId.value })
      if (res.data.code === 0) {
        message.success('删除成功')
        router.push('/my_space')
      } else {
        message.error('删除失败')
      }
    },
  })
}

// --------- 获取图片列表 --------

// KeepAlive 缓存标识（见 PageTransition 的 cachedPages）
defineOptions({ name: 'SpaceDetailPage' })

// 定义数据
const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const loading = ref(true)
const privateContentVersion = ref(0)

// 搜索条件
const searchParams = ref<API.PictureQueryRequest>({
  current: 1,
  pageSize: 12,
  sortField: 'createTime',
  sortOrder: 'descend',
})

// 请求仅能回填创建它的身份/空间；拒绝访问会使此前所有在途请求失效。
const loginUserStore = useLoginUserStore()
const pageIdentity = String(loginUserStore.loginUser.id ?? 'anonymous')
let contextVersion = 0
let spaceRequestVersion = 0
let pictureRequestVersion = 0
let disposed = false
let hasSpaceAccess = false
const requestContext = () => {
  const version = contextVersion
  const id = spaceId.value
  return () => !disposed && version === contextVersion && id === spaceId.value
    && pageIdentity === String(loginUserStore.loginUser.id ?? 'anonymous')
}

const clearPrivateContent = () => {
  // 清空父状态时一并销毁分享链接、弹窗和批量编辑表单等子组件本地状态。
  privateContentVersion.value++
  contextVersion++
  hasSpaceAccess = false
  space.value = {}
  dataList.value = []
  total.value = 0
  loading.value = false
}
// 渲染/离场动画尚未完成时，也不能继续保留前身份的内容与权限。
watch(() => loginUserStore.loginUser.id, clearPrivateContent, { flush: 'sync' })
onBeforeUnmount(() => {
  disposed = true
  contextVersion++
})

const fetchSpaceDetail = async () => {
  const isCurrent = requestContext()
  const requestVersion = ++spaceRequestVersion
  const canApply = () => isCurrent() && requestVersion === spaceRequestVersion
  if (!canApply()) return false
  try {
    const res = await getSpaceVoByIdUsingGet({ id: spaceId.value })
    if (!canApply()) return false
    if (res.data.code === 0 && res.data.data) {
      space.value = res.data.data
      hasSpaceAccess = true
      return true
    }
    clearPrivateContent()
    message.error('获取空间详情失败，' + res.data.message)
  } catch (e) {
    if (!canApply()) return false
    clearPrivateContent()
    message.error('获取空间详情失败：' + (e instanceof Error ? e.message : String(e)))
  }
  return false
}

// 获取数据；silent 用于 KeepAlive 激活时的静默刷新
const fetchData = async (silent = false) => {
  const isCurrent = requestContext()
  const requestVersion = ++pictureRequestVersion
  const canApply = () => isCurrent() && requestVersion === pictureRequestVersion
  if (!hasSpaceAccess || !canApply()) return
  if (!silent) loading.value = true
  const params = { spaceId: spaceId.value, ...searchParams.value }
  try {
    const res = await listPictureVoByPageWithCacheUsingPost(params)
    if (!canApply()) return
    if (res.data.code === 0 && res.data.data) {
      const records = res.data.data.records ?? []
      if (silent && dataList.value.length > 0) {
        const seen = new Set(dataList.value.map((p) => String(p.id)))
        const fresh = records.filter((p) => !seen.has(String(p.id)))
        if (fresh.length > 0) dataList.value = [...fresh, ...dataList.value]
      } else {
        dataList.value = records
      }
      total.value = Number(res.data.data.total ?? 0)
    } else {
      clearPrivateContent()
      message.error('获取数据失败，' + res.data.message)
    }
  } catch (e) {
    if (!canApply()) return
    clearPrivateContent()
    message.error('获取数据失败：' + (e instanceof Error ? e.message : String(e)))
  } finally {
    if (canApply()) loading.value = false
  }
}

// 激活时先重取详情与权限，拒绝后不允许图片回包恢复旧内容。
const refreshSpace = async (silent = false) => {
  const isCurrent = requestContext()
  if (await fetchSpaceDetail() && isCurrent()) await fetchData(silent)
}
onMounted(() => { void refreshSpace() })

let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  void refreshSpace(true)
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
  const isCurrent = requestContext()
  const requestVersion = ++pictureRequestVersion
  const canApply = () => isCurrent() && requestVersion === pictureRequestVersion
  if (!hasSpaceAccess || !canApply()) return
  loading.value = true
  try {
    const res = await searchPictureByColorUsingPost({ picColor: color, spaceId: spaceId.value })
    if (!canApply()) return
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data
      total.value = res.data.data.length
    } else {
      clearPrivateContent()
      message.error('获取数据失败，' + res.data.message)
    }
  } catch (e) {
    if (!canApply()) return
    clearPrivateContent()
    message.error('获取数据失败：' + (e instanceof Error ? e.message : String(e)))
  } finally {
    if (canApply()) loading.value = false
  }
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
  () => {
    clearPrivateContent()
    void refreshSpace()
  },
  { flush: 'sync' },
)
</script>

<style scoped>
#spaceDetailPage {
  margin-bottom: 16px;
}
</style>
