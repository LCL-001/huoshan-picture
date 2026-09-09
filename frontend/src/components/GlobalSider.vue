<template>
  <div id="globalSider">
    <a-layout-sider
      v-if="loginUserStore.loginUser.id"
      width="200"
      breakpoint="lg"
      collapsed-width="0"
      class="sider-panel"
    >
      <a-menu
        v-model:selectedKeys="current"
        mode="inline"
        :items="menuItems"
        @click="doMenuClick"
      />
    </a-layout-sider>
  </div>
</template>
<script lang="ts" setup>
import { computed, h, onBeforeUnmount, onMounted, ref, watchEffect } from 'vue'
import { PictureOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { useTeamSpaceStore } from '@/stores/useTeamSpaceStore.ts'
import { SPACE_TYPE_ENUM } from '@/constants/space.ts'
import { SPACE_LIST_REFRESH_EVENT } from '@/constants/events.ts'
import { message } from 'ant-design-vue'

const loginUserStore = useLoginUserStore()
const teamSpaceStore = useTeamSpaceStore()
const { teamSpaceList } = storeToRefs(teamSpaceStore)
const router = useRouter()

// 固定的菜单列表
const fixedMenuItems = [
  {
    key: '/',
    icon: () => h(PictureOutlined),
    label: '公共图库',
  },
  {
    key: '/my_space',
    label: '我的空间',
    icon: () => h(UserOutlined),
  },
  {
    key: '/add_space?type=' + SPACE_TYPE_ENUM.TEAM,
    label: '创建团队',
    icon: () => h(TeamOutlined),
  },
]

// 当前要高亮的菜单项
const current = ref<string[]>([])
const menuItems = computed(() => {
  // 如果用户没有团队空间，则只展示固定菜单
  if (teamSpaceList.value.length < 1) {
    return fixedMenuItems
  }
  // 如果用户有团队空间，则展示固定菜单和团队空间菜单
  // 展示团队空间分组
  const teamSpaceSubMenus = teamSpaceList.value.map((spaceUser) => {
    const space = spaceUser.space
    return {
      key: '/space/' + spaceUser.spaceId,
      label: space?.spaceName,
    }
  })
  const teamSpaceMenuGroup = {
    type: 'group',
    label: '我的团队',
    key: 'teamSpace',
    children: teamSpaceSubMenus,
  }
  return [...fixedMenuItems, teamSpaceMenuGroup]
})

const getPersistentSelectedKeys = (path: string, fullPath: string) => {
  if (fullPath === '/add_space?type=' + SPACE_TYPE_ENUM.TEAM) {
    return [fullPath]
  }
  if (path === '/') {
    return ['/']
  }
  if (path === '/my_space') {
    return ['/my_space']
  }
  if (path === '/add_space') {
    return ['/my_space']
  }
  if (path.startsWith('/space/')) {
    const spaceId = path.replace('/space/', '')
    const isTeamSpace = teamSpaceList.value.some((spaceUser) => {
      return String(spaceUser.spaceId) === spaceId
    })
    return [isTeamSpace ? path : '/my_space']
  }
  return [fullPath]
}

const updateCurrentSelectedKeys = () => {
  current.value = getPersistentSelectedKeys(
    router.currentRoute.value.path,
    router.currentRoute.value.fullPath,
  )
}

// 加载团队空间列表
const fetchTeamSpaceList = async () => {
  const res = await teamSpaceStore.fetchTeamSpaceList()
  if (res.data.code === 0 && res.data.data) {
    updateCurrentSelectedKeys()
  } else {
    message.error('加载我的团队空间失败，' + res.data.message)
  }
}

const refreshTeamSpaceList = (event?: Event) => {
  const deletedSpaceId = (event as CustomEvent<{ spaceId?: string | number }> | undefined)?.detail
    ?.spaceId
  if (deletedSpaceId) {
    teamSpaceStore.removeTeamSpace(deletedSpaceId)
    updateCurrentSelectedKeys()
  }
  if (loginUserStore.loginUser.id) {
    fetchTeamSpaceList()
  }
}

onMounted(() => {
  window.addEventListener(SPACE_LIST_REFRESH_EVENT, refreshTeamSpaceList)
})

onBeforeUnmount(() => {
  window.removeEventListener(SPACE_LIST_REFRESH_EVENT, refreshTeamSpaceList)
})

/**
 * 监听变量，改变时触发数据的重新加载
 */
watchEffect(() => {
  // 登录才加载
  if (loginUserStore.loginUser.id) {
    refreshTeamSpaceList()
  }
})

updateCurrentSelectedKeys()
// 监听路由变化，更新高亮菜单项
router.afterEach((to, from, next) => {
  current.value = getPersistentSelectedKeys(to.path, to.fullPath)
})

// 路由跳转事件
const doMenuClick = ({ key }: { key: string }) => {
  router.push(key)
}
</script>

<style scoped>
#globalSider {
  height: 100%;
}

#globalSider .sider-panel {
  background: transparent;
}

#globalSider :deep(.ant-layout-sider-children) {
  height: auto;
  min-height: calc(100vh - 112px);
  padding: 10px 10px 10px 0;
  background: transparent;
}

#globalSider :deep(.ant-menu) {
  background: transparent;
}

#globalSider :deep(.ant-menu-item) {
  height: 42px;
  margin: 4px 0;
  padding-inline: 14px !important;
  border-radius: 12px 0 0 12px;
  color: #475569;
  line-height: 42px;
  transition:
    color 0.2s ease,
    background 0.2s ease,
    transform 0.2s ease;
}

#globalSider :deep(.ant-menu-item:hover) {
  color: #1677ff;
  background: linear-gradient(90deg, #eef7ff 0%, rgba(238, 247, 255, 0.28) 100%);
  transform: translateX(1px);
}

#globalSider :deep(.ant-menu-item-selected) {
  color: #1677ff;
  background: linear-gradient(90deg, #e6f4ff 0%, rgba(246, 251, 255, 0.5) 100%);
  font-weight: 600;
  box-shadow: inset 3px 0 0 #1677ff;
}

#globalSider :deep(.ant-menu-item-selected::after) {
  display: none;
}

#globalSider :deep(.ant-menu-item .ant-menu-item-icon) {
  color: inherit;
  font-size: 16px;
}

#globalSider :deep(.ant-menu-item-group-title) {
  padding: 16px 14px 8px;
  color: #94a3b8;
  font-size: 12px;
  font-weight: 600;
}

@media (max-width: 991px) {
  #globalSider :deep(.ant-layout-sider-children) {
    min-height: auto;
    padding-right: 10px;
  }
}
</style>
