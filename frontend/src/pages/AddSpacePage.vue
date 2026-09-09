<template>
  <div id="addSpacePage">
    <h2 style="margin-bottom: 16px">
      {{ route.query?.id ? '修改' : '创建' }}{{ displaySpaceTypeText }}
    </h2>
    <!-- 空间信息表单 -->
    <a-form name="spaceForm" layout="vertical" :model="spaceForm" @finish="handleSubmit">
      <a-form-item name="spaceName" label="空间名称">
        <a-input v-model:value="spaceForm.spaceName" placeholder="请输入空间" allow-clear />
      </a-form-item>
      <a-form-item name="spaceLevel" label="空间级别">
        <a-select
          v-model:value="spaceForm.spaceLevel"
          style="min-width: 180px"
          placeholder="请选择空间级别"
          :options="SPACE_LEVEL_OPTIONS"
          allow-clear
        />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit" :loading="loading" style="width: 100%">
          提交
        </a-button>
      </a-form-item>
    </a-form>
    <!-- 空间级别介绍 -->
    <a-card title="空间级别介绍">
      <div class="space-level-intro">
        <div class="level-item">
          <span class="level-name">普通版</span>
          <span class="level-size">100MB空间</span>
        </div>
        <div class="level-item">
          <span class="level-name">专业版</span>
          <span class="level-size">1000MB空间</span>
        </div>
        <div class="level-item">
          <span class="level-name">旗舰版</span>
          <span class="level-size">10000MB空间</span>
        </div>
      </div>
      <a-typography-paragraph v-for="spaceLevel in spaceLevelList" :key="spaceLevel.level">
        {{ spaceLevel.text }}：大小 {{ formatSize(spaceLevel.maxSize) }}，数量
        {{ spaceLevel.maxCount }}
      </a-typography-paragraph>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  addSpaceUsingPost,
  getSpaceVoByIdUsingGet,
  listSpaceLevelUsingGet,
  updateSpaceUsingPost,
} from '@/api/spaceController.ts'
import { useRoute, useRouter } from 'vue-router'
import {
  SPACE_LEVEL_MAP,
  SPACE_LEVEL_OPTIONS,
  SPACE_TYPE_ENUM,
  SPACE_TYPE_MAP,
} from '@/constants/space.ts'
import { formatSize } from '../utils'
import { SPACE_LIST_REFRESH_EVENT } from '@/constants/events.ts'

const space = ref<API.SpaceVO>()
const spaceForm = reactive<API.SpaceAddRequest | API.SpaceEditRequest>({})
const loading = ref(false)

const route = useRoute()
// 空间类别，默认为私有空间
const spaceType = computed(() => {
  if (route.query?.type) {
    return Number(route.query.type)
  } else {
    return SPACE_TYPE_ENUM.PRIVATE
  }
})

// 编辑空间时优先展示接口返回的真实空间类型，避免团队空间被显示为私有空间。
const displaySpaceTypeText = computed(() => {
  if (route.query?.id && space.value?.spaceType === undefined) {
    return '空间'
  }
  return SPACE_TYPE_MAP[space.value?.spaceType ?? spaceType.value]
})

const spaceLevelList = ref<API.SpaceLevel[]>([])

// 获取空间级别
const fetchSpaceLevelList = async () => {
  const res = await listSpaceLevelUsingGet()
  if (res.data.code === 0 && res.data.data) {
    spaceLevelList.value = res.data.data
  } else {
    message.error('获取空间级别失败，' + res.data.message)
  }
}

onMounted(() => {
  fetchSpaceLevelList()
})

const router = useRouter()

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: any) => {
  const spaceId = space.value?.id
  loading.value = true
  let res
  if (spaceId) {
    // 更新
    res = await updateSpaceUsingPost({
      id: spaceId,
      ...spaceForm,
    })
  } else {
    // 创建
    res = await addSpaceUsingPost({
      ...spaceForm,
      spaceType: spaceType.value,
    })
  }
  // 操作成功
  if (res.data.code === 0 && res.data.data) {
    message.success('操作成功')
    window.dispatchEvent(new Event(SPACE_LIST_REFRESH_EVENT))
    // 跳转到空间详情页
    router.push({
      path: `/space/${res.data.data}`,
    })
  } else {
    message.error('操作失败，' + res.data.message)
  }
  loading.value = false
}

// 获取老数据
const getOldSpace = async () => {
  // 获取到 id
  const id = route.query?.id
  if (id) {
    const res = await getSpaceVoByIdUsingGet({
      id,
    })
    if (res.data.code === 0 && res.data.data) {
      const data = res.data.data
      space.value = data
      // 填充表单
      spaceForm.spaceName = data.spaceName
      spaceForm.spaceLevel = data.spaceLevel
    }
  }
}

onMounted(() => {
  getOldSpace()
})
</script>

<style scoped>
#addSpacePage {
  max-width: 720px;
  margin: 0 auto;
}

.space-level-intro {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.level-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 12px;
  background-color: #f5f5f5;
  border-radius: 4px;
  transition: background-color 0.3s;
}

.level-item:hover {
  background-color: #e8e8e8;
}

.level-name {
  font-weight: 500;
  color: #333;
}

.level-size {
  color: #1890ff;
  font-weight: 500;
}
</style>
