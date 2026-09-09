<template>
  <div id="basicLayout">
    <a-layout class="app-frame">
      <a-layout-header class="header" :class="{ 'header-hidden': !headerVisible }">
        <GlobalHeader @toggle-mobile-menu="mobileMenuVisible = !mobileMenuVisible" />
      </a-layout-header>
      <a-layout-content class="content">
        <PageTransition />
      </a-layout-content>
      <a-layout-footer class="app-footer">
        <span>© 2026 火山图库</span>
        <a-divider type="vertical" />
        <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener">粤ICP备2026075420号-1</a>
        <a-divider type="vertical" />
        <a href="https://beian.mps.gov.cn/#/query/webSearch?code=44078102441011" target="_blank" rel="noopener">
          <img src="/beian-icon.png" style="width: 14px; height: 14px; vertical-align: -1px; margin-right: 2px" />
          粤公网安备44078102441011号
        </a>
      </a-layout-footer>
    </a-layout>

    <!-- 桌面端浮动侧边栏 -->
    <div v-if="loginUserStore.loginUser.id && !isMobile" class="floating-sider" @mouseenter="siderHover = true" @mouseleave="siderHover = false">
      <div class="floating-sider-inner" :class="{ expanded: siderHover }">
        <a-menu
          v-model:selectedKeys="siderCurrent"
          mode="inline"
          :inline-collapsed="!siderHover"
          :items="siderMenuItems"
          @click="doSiderMenuClick"
        />
      </div>
    </div>

    <!-- 回到顶部 -->
    <div v-if="showBackTop" class="back-top-btn" @click="scrollToTop">
      <UpOutlined />
    </div>

    <!-- 移动端底部胶囊导航 -->
    <div v-if="isMobile" class="mobile-bottom-nav">
      <div
        class="bottom-nav-item"
        :class="{ active: currentRoute === '/' || currentRoute === '' }"
        @click="router.push('/')"
      >
        <HomeOutlined />
        <span class="bottom-nav-label">首页</span>
      </div>
      <!-- 上传图片入口（公共图库） -->
      <div
        v-if="!currentRoute.startsWith('/add_picture')"
        class="bottom-nav-item"
        @click="loginUserStore.loginUser.id ? router.push('/add_picture') : router.push('/user/login')"
      >
        <PlusOutlined />
        <span class="bottom-nav-label">上传</span>
      </div>
      <!-- 论坛暂不启用 -->
      <!-- <div
        class="bottom-nav-item"
        :class="{ active: currentRoute === '/square' || currentRoute.startsWith('/square') }"
        @click="router.push('/square')"
      >
        <ReadOutlined />
        <span class="bottom-nav-label">论坛</span>
      </div> -->
      <!-- 论坛暂不启用 -->
      <!-- <div
        class="bottom-nav-item"
        :class="{ active: currentRoute.startsWith('/user/') }"
        @click="loginUserStore.loginUser.id ? router.push('/user/' + loginUserStore.loginUser.id) : router.push('/user/login')"
      >
        <UserOutlined />
        <span class="bottom-nav-label">主页</span>
      </div> -->
      <div
        class="bottom-nav-item"
        :class="{ active: currentRoute === '/my_space' || (currentRoute.startsWith('/space/') && !isTeamSpaceActive) }"
        @click="loginUserStore.loginUser.id ? router.push('/my_space') : router.push('/user/login')"
      >
        <AppstoreOutlined />
        <span class="bottom-nav-label">空间</span>
      </div>
      <!-- 团队tab -->
      <a-popover
        v-if="loginUserStore.loginUser.id && teamSpaceList.length > 0"
        trigger="click"
        placement="top"
        :open="spacePopoverOpen"
        @openChange="(v: boolean) => spacePopoverOpen = v"
      >
        <div
          class="bottom-nav-item"
          :class="{ active: isTeamSpaceActive }"
          @click="spacePopoverOpen = !spacePopoverOpen"
        >
          <TeamOutlined />
          <span class="bottom-nav-label">团队</span>
        </div>
        <template #content>
          <div class="space-popover-list">
            <div
              v-for="su in teamSpaceList" :key="su.spaceId"
              class="space-popover-item"
              @click="router.push('/space/' + su.spaceId); spacePopoverOpen = false"
            >
              <TeamOutlined style="font-size: 16px; margin-right: 8px" />
              {{ su.space?.spaceName }}
            </div>
            <a-divider style="margin: 4px 0" />
            <div class="space-popover-item" style="color: #1677ff" @click="router.push('/add_space?type=' + SPACE_TYPE_ENUM.TEAM); spacePopoverOpen = false">
              <PlusCircleOutlined style="font-size: 16px; margin-right: 8px" />
              创建团队
            </div>
          </div>
        </template>
      </a-popover>
      <div
        v-else
        class="bottom-nav-item"
        :class="{ active: currentRoute.startsWith('/add_space') }"
        @click="loginUserStore.loginUser.id ? router.push('/add_space?type=' + SPACE_TYPE_ENUM.TEAM) : router.push('/user/login')"
      >
        <TeamOutlined />
        <span class="bottom-nav-label">团队</span>
      </div>
    </div>

    <!-- 移动端汉堡抽屉 -->
    <a-drawer
      v-if="loginUserStore.loginUser.id && isMobile"
      v-model:visible="mobileMenuVisible"
      placement="left"
      :width="220"
      :closable="true"
      wrap-class-name="mobile-sider-drawer"
    >
      <a-menu
        v-model:selectedKeys="siderCurrent"
        mode="inline"
        :items="siderMenuItems"
        @click="doSiderMenuClick"
      />
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref, watchEffect } from 'vue'
import { useRouter } from 'vue-router'
import {
  AppstoreOutlined,
  DatabaseOutlined,
  HomeOutlined,
  PictureOutlined,
  PlusCircleOutlined,
  PlusOutlined,
  ReadOutlined,
  TeamOutlined,
  UpOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import GlobalHeader from '@/components/GlobalHeader.vue'
import PageTransition from '@/components/PageTransition.vue'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { useTeamSpaceStore } from '@/stores/useTeamSpaceStore.ts'
import { storeToRefs } from 'pinia'
import { SPACE_TYPE_ENUM } from '@/constants/space.ts'
import { SPACE_LIST_REFRESH_EVENT } from '@/constants/events.ts'
import type { MenuProps } from 'ant-design-vue'

const router = useRouter()
const loginUserStore = useLoginUserStore()
const teamSpaceStore = useTeamSpaceStore()
const { teamSpaceList } = storeToRefs(teamSpaceStore)

// 响应式
const isMobile = ref(window.innerWidth < 768)
const showBackTop = ref(false)
const headerVisible = ref(true)
let lastScrollY = 0
const onResize = () => { isMobile.value = window.innerWidth < 768 }
const onScroll = () => {
  const currentY = window.scrollY
  showBackTop.value = currentY > 400
  if (currentY < 64) {
    headerVisible.value = true
  } else {
    headerVisible.value = currentY < lastScrollY
  }
  lastScrollY = currentY
}
const scrollToTop = () => { window.scrollTo({ top: 0, behavior: 'smooth' }) }
onMounted(() => {
  window.addEventListener('resize', onResize)
  window.addEventListener('scroll', onScroll, { passive: true })
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('scroll', onScroll)
})

// 侧边栏状态
const siderHover = ref(false)
const mobileMenuVisible = ref(false)
const spacePopoverOpen = ref(false)
const siderCurrent = ref<string[]>(['/'])

// 菜单数据
const fixedMenuItems = computed(() => {
  const uid = loginUserStore.loginUser.id
  return [
    { key: '/', icon: () => h(HomeOutlined), label: '首页' },
    // 论坛暂不启用
    // { key: '/square', icon: () => h(ReadOutlined), label: '论坛' },
    // ...(uid ? [{ key: '/user/' + uid, icon: () => h(UserOutlined), label: '我的主页' }] : []),
    { key: '/add_picture', icon: () => h(PlusCircleOutlined), label: '上传图片' },
    { key: '/my_space', icon: () => h(AppstoreOutlined), label: '我的空间' },
    { key: '/add_space?type=' + SPACE_TYPE_ENUM.TEAM, icon: () => h(TeamOutlined), label: '创建团队' },
  ]
})

// 管理员菜单（与顶栏一致，仅 admin 可见）
const adminMenuItems = computed(() => {
  if (loginUserStore.loginUser.userRole !== 'admin') return []
  return [
    { key: '/admin/userManage', icon: () => h(UserOutlined), label: '用户管理' },
    { key: '/admin/pictureManage', icon: () => h(PictureOutlined), label: '图片管理' },
    { key: '/admin/spaceManage', icon: () => h(DatabaseOutlined), label: '空间管理' },
  ]
})

const siderMenuItems = computed<MenuProps['items']>(() => {
  const items: MenuProps['items'] = [...fixedMenuItems.value]
  const admin = adminMenuItems.value
  if (admin.length > 0) {
    items.push({ type: 'divider' }, ...admin)
  }
  if (teamSpaceList.value.length < 1) return items
  const teamSpaceSubMenus = teamSpaceList.value.map((su) => ({
    key: '/space/' + su.spaceId,
    icon: () => h(TeamOutlined),
    label: su.space?.spaceName,
  }))
  return [
    ...items,
    { type: 'divider' },
    ...teamSpaceSubMenus,
  ]
})

const currentRoute = computed(() => router.currentRoute.value.path)

const isTeamSpaceActive = computed(() => {
  const p = currentRoute.value
  if (p.startsWith('/space/')) {
    const sid = p.replace('/space/', '')
    return teamSpaceList.value.some(su => String(su.spaceId) === sid)
  }
  return p.startsWith('/add_space')
})

const doSiderMenuClick = ({ key }: { key: string }) => {
  router.push(key)
  mobileMenuVisible.value = false
}

// 高亮
watchEffect(() => {
  const p = router.currentRoute.value.path
  if (p === '/') siderCurrent.value = ['/']
  // else if (p === '/square') siderCurrent.value = ['/square']
  else if (p === '/my_space') siderCurrent.value = ['/my_space']
  else if (p === '/add_space') siderCurrent.value = ['/my_space']
  // else if (p.startsWith('/user/')) siderCurrent.value = [p]
  else if (p.startsWith('/space/')) {
    const sid = p.replace('/space/', '')
    const found = teamSpaceList.value.some(su => String(su.spaceId) === sid)
    siderCurrent.value = found ? [p] : ['/my_space']
  } else siderCurrent.value = [p]
})

// 加载团队空间
const fetchTeamSpaceList = async () => {
  if (loginUserStore.loginUser.id) {
    await teamSpaceStore.fetchTeamSpaceList()
  }
}

watchEffect(() => { fetchTeamSpaceList() })

// 创建/编辑空间后刷新团队空间菜单（AddSpacePage 成功后派发该事件）
const onSpaceListRefresh = () => { fetchTeamSpaceList() }
onMounted(() => {
  window.addEventListener(SPACE_LIST_REFRESH_EVENT, onSpaceListRefresh)
})
onBeforeUnmount(() => {
  window.removeEventListener(SPACE_LIST_REFRESH_EVENT, onSpaceListRefresh)
})
</script>

<style scoped>
/* 框架 */
.app-frame {
  min-height: 100vh;
  background:
    radial-gradient(circle at 14% 0%, var(--grad-ambient-a), transparent 32%),
    radial-gradient(circle at 86% 100%, var(--grad-ambient-b), transparent 28%),
    linear-gradient(135deg, #f6faff 0%, #ffffff 52%, #f3f8ff 100%);
  animation: bg-shift 20s ease-in-out infinite alternate;
}

@keyframes bg-shift {
  0% {
    background:
      radial-gradient(circle at 14% 0%, var(--grad-ambient-a), transparent 32%),
      radial-gradient(circle at 86% 100%, var(--grad-ambient-b), transparent 28%),
      linear-gradient(135deg, #f6faff 0%, #ffffff 52%, #f3f8ff 100%);
  }
  50% {
    background:
      radial-gradient(circle at 24% 10%, var(--grad-ambient-a), transparent 32%),
      radial-gradient(circle at 76% 90%, var(--grad-ambient-b), transparent 28%),
      linear-gradient(135deg, #f7faff 0%, #fefefe 52%, #f4f9ff 100%);
  }
  100% {
    background:
      radial-gradient(circle at 8% -4%, var(--grad-ambient-a), transparent 32%),
      radial-gradient(circle at 92% 104%, var(--grad-ambient-b), transparent 28%),
      linear-gradient(135deg, #f5f9ff 0%, #ffffff 52%, #f2f7ff 100%);
  }
}

:deep(.ant-layout) {
  background: transparent;
}

/* 顶部栏 */
.header {
  position: sticky;
  top: 0;
  z-index: 20;
  height: 64px;
  padding-inline: 28px;
  background: var(--glass-bg-strong);
  backdrop-filter: blur(var(--glass-blur));
  border-bottom: 1px solid var(--glass-border);
  box-shadow: 0 4px 20px rgba(37, 99, 235, 0.04);
  color: unset;
  line-height: 64px;
  transition: transform 0.3s ease;
}

.header-hidden {
  transform: translateY(-100%);
}

.app-frame {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.content {
  flex: 1;
  min-width: 0;
  padding: 28px 28px 28px 84px;
  background: transparent;
  position: relative;
  overflow: hidden;
}

/* 内容区装饰浮动圆 */
.content::before,
.content::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
  z-index: 0;
}

.content::before {
  width: 300px;
  height: 300px;
  top: -80px;
  right: -60px;
  background: radial-gradient(circle, rgba(22, 119, 255, 0.03), transparent 70%);
  animation: float-slow 25s ease-in-out infinite alternate;
}

.content::after {
  width: 200px;
  height: 200px;
  bottom: 10%;
  left: 10%;
  background: radial-gradient(circle, rgba(34, 211, 238, 0.03), transparent 70%);
  animation: float-slow 20s ease-in-out infinite alternate-reverse;
}

@keyframes float-slow {
  0% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(20px, -15px) scale(1.05); }
  66% { transform: translate(-10px, 10px) scale(0.95); }
  100% { transform: translate(15px, -5px) scale(1.02); }
}

/* 浮动侧边栏 */
.floating-sider {
  position: fixed;
  left: 16px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 30;
  max-height: calc(100vh - 160px);
}

.floating-sider-inner {
  width: 52px;
  max-height: calc(100vh - 160px);
  overflow-y: auto;
  overflow-x: hidden;
  background: var(--glass-bg);
  backdrop-filter: blur(var(--glass-blur)) saturate(140%);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(140%);
  border: 1px solid var(--glass-border);
  border-radius: 18px;
  box-shadow:
    0 4px 32px rgba(22, 119, 255, 0.06),
    var(--glass-highlight);
  padding: 6px 0;
  transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1), box-shadow 0.25s ease;
}

.floating-sider-inner.expanded {
  width: 196px;
  box-shadow:
    0 8px 40px rgba(22, 119, 255, 0.1),
    var(--glass-highlight);
}

.floating-sider :deep(.ant-menu) {
  background: transparent;
  border: none;
  padding: 0 4px;
}

.floating-sider :deep(.ant-menu-item) {
  height: 38px;
  margin: 1px 4px;
  border-radius: 10px;
  padding: 0 12px !important;
  color: #64748b;
  line-height: 38px;
  font-size: 13px;
  transition: all 0.15s ease;
}

.floating-sider :deep(.ant-menu-item:hover) {
  color: #1677ff;
  background: rgba(22, 119, 255, 0.08);
}

.floating-sider :deep(.ant-menu-item-selected) {
  color: #1677ff;
  background: rgba(22, 119, 255, 0.12);
  font-weight: 600;
}

.floating-sider :deep(.ant-menu-item .anticon) {
  font-size: 20px;
}

.floating-sider :deep(.ant-menu-inline-collapsed) {
  width: 52px;
}

.floating-sider :deep(.ant-menu-inline-collapsed > .ant-menu-item) {
  padding-inline: 0 !important;
  text-align: center;
}

.floating-sider-inner:not(.expanded) :deep(.ant-menu-item-selected) {
  background: transparent;
}

.floating-sider-inner:not(.expanded) :deep(.ant-menu-item-selected .anticon) {
  color: #1677ff;
  background: rgba(22, 119, 255, 0.12);
  border-radius: 50%;
  padding: 6px;
}

.floating-sider :deep(.ant-menu-item-divider) {
  margin: 6px 12px;
  border-color: rgba(0, 0, 0, 0.05);
}

.floating-sider-inner::-webkit-scrollbar {
  width: 0;
}

/* 回到顶部 */
.back-top-btn {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 40;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--glass-bg-strong);
  backdrop-filter: blur(var(--glass-blur));
  border: 1px solid var(--glass-border);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #64748b;
  font-size: 16px;
  transition: all 0.2s ease;
}

.back-top-btn:hover {
  color: #1677ff;
  box-shadow:
    0 4px 16px rgba(22, 119, 255, 0.15),
    0 0 0 4px rgba(22, 119, 255, 0.08);
  transform: translateY(-2px);
}

/* 底部胶囊导航 */
.mobile-bottom-nav {
  position: fixed;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 30;
  display: flex;
  gap: 4px;
  padding: 6px;
  background: var(--glass-bg-strong);
  backdrop-filter: blur(var(--glass-blur));
  border: 1px solid var(--glass-border);
  border-radius: 24px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);
}

.bottom-nav-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  width: 56px;
  height: 48px;
  border-radius: 18px;
  cursor: pointer;
  color: #94a3b8;
  transition: color 0.2s, background 0.2s;
  font-size: 20px;
}

.bottom-nav-item.active {
  color: #1677ff;
  background: rgba(22, 119, 255, 0.1);
}

.bottom-nav-label {
  font-size: 10px;
  line-height: 1;
}

/* 空间popover */
.space-popover-list {
  min-width: 160px;
  max-width: 220px;
}

.space-popover-item {
  display: flex;
  align-items: center;
  padding: 8px 4px;
  cursor: pointer;
  border-radius: 6px;
  font-size: 13px;
  color: #334155;
  transition: background 0.15s;
}

.space-popover-item:hover {
  background: rgba(22, 119, 255, 0.06);
}

/* 底部 footer */
.app-footer {
  text-align: center;
  padding: 8px 24px;
  background: transparent;
  color: #94a3b8;
  font-size: 12px;
}

.app-footer a {
  color: #94a3b8;
  text-decoration: none;
}

.app-footer a:hover {
  color: #1677ff;
}

/* 移动端适配 */
@media (max-width: 768px) {
  .header {
    padding-inline: 14px;
  }

  .content {
    padding: 16px 12px 88px;
  }

  .app-footer {
    padding: 6px 12px;
    font-size: 11px;
  }

  .back-top-btn {
    bottom: 80px;
    right: 12px;
    width: 36px;
    height: 36px;
    font-size: 14px;
  }
}

:deep(.mobile-sider-drawer .ant-drawer-body) {
  padding: 12px 0;
}
</style>
