<template>
  <div class="picture-search-form">
    <!-- 移动端搜索折叠按钮 -->
    <a-button class="search-toggle-btn" @click="showFilters = !showFilters">
      <SearchOutlined /> 搜索条件 {{ showFilters ? '▲' : '▼' }}
    </a-button>
    <!-- 搜索表单 -->
    <a-form v-show="showFilters || !isMobile" name="searchForm" layout="inline" :model="searchParams" @finish="doSearch">
      <a-form-item label="关键词" name="searchText">
        <a-input
          v-model:value="searchParams.searchText"
          placeholder="从名称和简介搜索"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="category" label="分类">
        <a-auto-complete
          v-model:value="searchParams.category"
          style="min-width: 180px"
          placeholder="请输入分类"
          :options="categoryOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="tags" label="标签">
        <a-select
          v-model:value="searchParams.tags"
          style="min-width: 180px"
          mode="tags"
          placeholder="请输入标签"
          :options="tagOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="日期" name="dateRange">
        <a-range-picker
          style="width: 400px"
          show-time
          v-model:value="dateRange"
          :placeholder="['编辑开始时间', '编辑结束时间']"
          format="YYYY/MM/DD HH:mm:ss"
          :presets="rangePresets"
          @change="onRangeChange"
        />
      </a-form-item>
      <a-form-item label="名称" name="name">
        <a-input v-model:value="searchParams.name" placeholder="请输入名称" allow-clear />
      </a-form-item>
      <a-form-item label="简介" name="introduction">
        <a-input v-model:value="searchParams.introduction" placeholder="请输入简介" allow-clear />
      </a-form-item>
      <a-form-item label="宽度" name="picWidth">
        <a-input-number v-model:value="searchParams.picWidth" />
      </a-form-item>
      <a-form-item label="高度" name="picHeight">
        <a-input-number v-model:value="searchParams.picHeight" />
      </a-form-item>
      <a-form-item label="格式" name="picFormat">
        <a-input v-model:value="searchParams.picFormat" placeholder="请输入格式" allow-clear />
      </a-form-item>
      <a-form-item>
        <a-space>
          <a-button type="primary" html-type="submit" style="width: 96px">搜索</a-button>
          <a-button html-type="reset" @click="doClear">重置</a-button>
        </a-space>
      </a-form-item>
    </a-form>
  </div>
</template>
<script lang="ts" setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { SearchOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import {listPictureTagCategoryUsingGet, listPictureVoByPageUsingPost} from '@/api/pictureController.ts'
import { message } from 'ant-design-vue'

interface Props {
  onSearch?: (searchParams: API.PictureQueryRequest) => void
}

const props = defineProps<Props>()

// 移动端折叠
const showFilters = ref(false)
const isMobile = ref(window.innerWidth < 768)
const onResize = () => { isMobile.value = window.innerWidth < 768 }
onMounted(() => window.addEventListener('resize', onResize))
onUnmounted(() => window.removeEventListener('resize', onResize))

// 搜索条件
const searchParams = reactive<API.PictureQueryRequest>({})

// 搜索数据
const doSearch = () => {
  props.onSearch?.(searchParams)
}

// 标签和分类选项
const categoryOptions = ref<string[]>([])
const tagOptions = ref<string[]>([])

/**
 * 获取标签和分类选项
 * @param values
 */
const getTagCategoryOptions = async () => {
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    tagOptions.value = (res.data.data.tagList ?? []).map((data: string) => {
      return {
        value: data,
        label: data,
      }
    })
    categoryOptions.value = (res.data.data.categoryList ?? []).map((data: string) => {
      return {
        value: data,
        label: data,
      }
    })
  } else {
    message.error('获取标签分类列表失败，' + res.data.message)
  }
}

onMounted(() => {
  getTagCategoryOptions()
})

const dateRange = ref<[]>([])

/**
 * 日期范围更改时触发
 * @param dates
 * @param dateStrings
 */
const onRangeChange = (dates: any[], dateStrings: string[]) => {
  if (dates?.length >= 2) {
    searchParams.startEditTime = dates[0].toDate()
    searchParams.endEditTime = dates[1].toDate()
  } else {
    searchParams.startEditTime = undefined
    searchParams.endEditTime = undefined
  }
}

// 时间范围预设
const rangePresets = ref([
  { label: '过去 7 天', value: [dayjs().add(-7, 'd'), dayjs()] },
  { label: '过去 14 天', value: [dayjs().add(-14, 'd'), dayjs()] },
  { label: '过去 30 天', value: [dayjs().add(-30, 'd'), dayjs()] },
  { label: '过去 90 天', value: [dayjs().add(-90, 'd'), dayjs()] },
])

// 清理
const doClear = () => {
  // 取消所有对象的值
  Object.keys(searchParams).forEach((key) => {
    searchParams[key] = undefined
  })
  // 日期筛选项单独清空，必须定义为空数组
  dateRange.value = []
  // 清空后重新搜索
  props.onSearch?.(searchParams)
}
</script>

<style scoped>
.picture-search-form :deep(.ant-form-item) {
  margin-top: 16px;
  /* 表单项依次入场；移动端从 display:none 展开时动画会重新播放 */
  animation: fadeInUp 0.4s cubic-bezier(0.34, 1.56, 0.64, 1) both;
}

.picture-search-form :deep(.ant-form-item:nth-child(2)) { animation-delay: 40ms; }
.picture-search-form :deep(.ant-form-item:nth-child(3)) { animation-delay: 80ms; }
.picture-search-form :deep(.ant-form-item:nth-child(4)) { animation-delay: 120ms; }
.picture-search-form :deep(.ant-form-item:nth-child(5)) { animation-delay: 160ms; }
.picture-search-form :deep(.ant-form-item:nth-child(6)) { animation-delay: 200ms; }
.picture-search-form :deep(.ant-form-item:nth-child(7)) { animation-delay: 240ms; }
.picture-search-form :deep(.ant-form-item:nth-child(8)) { animation-delay: 280ms; }
.picture-search-form :deep(.ant-form-item:nth-child(9)) { animation-delay: 320ms; }
.picture-search-form :deep(.ant-form-item:nth-child(10)) { animation-delay: 360ms; }

.search-toggle-btn {
  display: none;
  margin-bottom: 8px;
  transition:
    transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.2s ease;
}

.search-toggle-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 14px rgba(22, 119, 255, 0.15);
}

.search-toggle-btn:active {
  transform: scale(0.96);
}

@media (max-width: 768px) {
  .search-toggle-btn {
    display: inline-block;
  }

  .picture-search-form :deep(.ant-form-item) {
    display: block;
    margin-right: 0;
  }
}
</style>
