<template>
  <div id="globalHeader">
    <a-row :wrap="false" class="header-row">
      <a-col flex="220px" class="brand-col">
        <router-link to="/">
          <div class="title-bar">
            <img class="logo" src="../assets/logo.svg" alt="logo" />
            <div class="title">火山图库</div>
          </div>
        </router-link>
      </a-col>
      <a-col flex="auto" class="nav-col">
        <a-menu
          v-model:selectedKeys="current"
          mode="horizontal"
          :items="items"
          @click="doMenuClick"
          class="desktop-nav"
        />
      </a-col>
      <a-col flex="40px" class="mobile-menu-col">
        <MenuOutlined class="mobile-menu-icon" @click="emit('toggle-mobile-menu')" />
      </a-col>
      <!-- 用户信息展示栏 -->
      <a-col flex="132px" class="user-col">
        <div class="user-login-status">
          <div v-if="loginUserStore.loginUser.id" style="display: flex; align-items: center; gap: 12px">
            <a-badge :count="unreadCount" :overflow-count="99">
              <BellOutlined style="font-size: 18px; cursor: pointer" @click="openNotifications" />
            </a-badge>
            <a-dropdown>
              <a-space class="user-entry">
                <a-avatar :size="28" :src="loginUserStore.loginUser.userAvatar" />
                <span class="user-name">{{ loginUserStore.loginUser.userName ?? '无名' }}</span>
              </a-space>
              <template #overlay>
                <a-menu>
                  <a-menu-item @click="openUserProfile">
                    <UserOutlined />
                    个人信息
                  </a-menu-item>
                  <!-- 论坛暂不启用
                  <a-menu-item @click="goToMyProfile">
                    <UserOutlined />
                    我的主页
                  </a-menu-item>
                  -->
                  <a-menu-item>
                    <router-link to="/my_space">
                      <AppstoreOutlined />
                      我的空间
                    </router-link>
                  </a-menu-item>
                  <a-menu-item @click="doLogout">
                    <LogoutOutlined />
                    退出登录
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
          <div v-else>
            <a-button type="primary" href="/user/login">登录</a-button>
          </div>
        </div>
      </a-col>
    </a-row>
    <a-modal
      v-model:visible="userProfileVisible"
      title="个人信息"
      :footer="false"
      width="520px"
      class="user-profile-modal"
    >
      <a-spin :spinning="userProfileLoading || saving">
        <div class="user-profile-panel">
          <div class="user-profile-hero">
            <div
              class="avatar-upload-wrapper"
              :class="{ 'is-editing': isEditing }"
              @click="isEditing && handleAvatarClick()"
            >
              <a-spin :spinning="avatarUploading">
                <a-avatar
                  :size="72"
                  :src="editForm.userAvatar || userProfile.userAvatar || loginUserStore.loginUser.userAvatar"
                />
              </a-spin>
              <div v-if="isEditing" class="avatar-overlay">
                <CameraOutlined />
                <span>更换</span>
              </div>
            </div>
            <input
              ref="fileInputRef"
              type="file"
              accept="image/*"
              style="display: none"
              @change="onAvatarFileChange"
            />
            <div class="user-profile-title">
              <div v-if="isEditing" class="user-profile-edit-fields">
                <a-input
                  v-model:value="editForm.userName"
                  placeholder="昵称"
                  style="margin-bottom: 8px"
                />
                <a-input v-model:value="editForm.userProfile" placeholder="简介" />
                <a-divider style="margin: 12px 0 8px" />
                <div v-if="userProfile.email" class="bind-email-hint">当前邮箱：{{ userProfile.email }}</div>
                <a-input
                  v-model:value="bindForm.email"
                  placeholder="绑定邮箱"
                  style="margin-bottom: 8px"
                />
                <div class="captcha-row">
                  <a-input v-model:value="bindCaptchaCode" placeholder="图形验证码" :maxlength="4" />
                  <img
                    v-if="bindCaptchaImg"
                    :src="bindCaptchaImg"
                    alt="验证码，点击刷新"
                    title="点击刷新"
                    class="captcha-img"
                    @click="refreshBindCaptcha"
                  />
                </div>
                <div class="captcha-row" style="margin-top: 8px">
                  <a-input v-model:value="bindForm.emailCode" placeholder="邮箱验证码" :maxlength="6" />
                  <a-button class="send-btn" :disabled="bindCountdown > 0" @click="doSendBindCode">
                    {{ bindCountdown > 0 ? bindCountdown + 's' : '获取验证码' }}
                  </a-button>
                </div>
                <a-button
                  type="primary"
                  ghost
                  block
                  style="margin-top: 8px"
                  :loading="binding"
                  @click="doBindEmail"
                >
                  绑定邮箱
                </a-button>
              </div>
              <template v-else>
                <div class="user-profile-name">{{ userProfile.userName ?? '无名' }}</div>
                <a-tag color="blue">{{ userRoleText }}</a-tag>
              </template>
            </div>
          </div>
          <a-descriptions :column="1" size="small" bordered>
            <a-descriptions-item label="用户 ID">
              {{ userProfile.id ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="账号">
              {{ userProfile.userAccount ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item v-if="!isEditing" label="昵称">
              {{ userProfile.userName ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item v-if="!isEditing" label="简介">
              {{ userProfile.userProfile ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="会员编号">
              {{ userProfile.vipNumber ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="会员到期时间">
              {{ formatDateTime(userProfile.vipExpireTime) }}
            </a-descriptions-item>
            <a-descriptions-item label="注册时间">
              {{ formatDateTime(userProfile.createTime) }}
            </a-descriptions-item>
          </a-descriptions>
          <div class="user-profile-actions">
            <template v-if="!isEditing">
              <a-button type="primary" block @click="startEdit">
                <template #icon><EditOutlined /></template>
                编辑资料
              </a-button>
            </template>
            <template v-else>
              <a-space style="width: 100%">
                <a-button @click="cancelEdit">取消</a-button>
                <a-button type="primary" :loading="saving" @click="handleSave">保存</a-button>
              </a-space>
            </template>
          </div>
        </div>
      </a-spin>
    </a-modal>
    <!-- 通知抽屉 -->
    <a-drawer
      v-model:visible="notifVisible"
      placement="right"
      width="400"
    >
      <template #title>
        <a-flex justify="space-between" align="center" style="width: 100%">
          <span>消息通知</span>
          <a-button v-if="notifications.length" type="link" size="small" danger @click="doClearNotifications">清空</a-button>
        </a-flex>
      </template>
      <a-spin :spinning="notifLoading">
        <div v-if="notifications.length">
          <div
            v-for="n in notifications" :key="n.id"
            :style="{ padding: '10px 0', borderBottom: '1px solid #f0f0f0', opacity: n.isRead ? 0.6 : 1 }"
          >
            <div style="font-size: 13px; margin-bottom: 4px">{{ n.summary }}</div>
            <a-flex justify="space-between">
              <span style="color: #999; font-size: 11px">{{ formatRelativeTime(n.createTime) }}</span>
              <a-button type="link" size="small" danger @click="doDeleteNotification(n.id)">删除</a-button>
            </a-flex>
          </div>
        </div>
        <a-empty v-else description="暂无通知" />
      </a-spin>
    </a-drawer>
  </div>
</template>
<script lang="ts" setup>
import { computed, h, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  AppstoreOutlined,
  BellOutlined,
  CameraOutlined,
  EditOutlined,
  HomeOutlined,
  LogoutOutlined,
  MenuOutlined,
  ReadOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import type { MenuProps } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { editUserUsingPost, getUserVoByIdUsingGet, userLogoutUsingPost } from '@/api/userController.ts'
import { bindEmailUsingPost } from '@/api/authCaptcha.ts'
import { useImgCaptcha, useEmailCode } from '@/composables/useCaptcha.ts'
import { uploadFileUsingPost } from '@/api/fileController.ts'
import { clearNotificationsUsingPost, clearUnreadUsingPost, deleteNotificationUsingPost, getUnreadCountUsingGet, listNotificationsUsingGet } from '@/api/postController.ts'
import { formatDateTime, formatRelativeTime } from '@/utils'

const emit = defineEmits(['toggle-mobile-menu'])
const loginUserStore = useLoginUserStore()

// 未经过滤的菜单项
const originItems = [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '主页',
    title: '主页',
  },
  // 论坛暂不启用
  // {
  //   key: '/square',
  //   label: '论坛',
  //   title: '广场',
  // },
  {
    key: '/add_picture',
    label: '上传图片',
    title: '上传图片',
  },
  {
    key: '/admin/userManage',
    label: '用户管理',
    title: '用户管理',
  },
  {
    key: '/admin/pictureManage',
    label: '图片管理',
    title: '图片管理',
  },
  {
    key: '/admin/spaceManage',
    label: '空间管理',
    title: '空间管理',
  },
]

// 根据权限过滤菜单项
const filterMenus = (menus = [] as MenuProps['items']) => {
  return menus?.filter((menu) => {
    // 管理员才能看到 /admin 开头的菜单
    if (String(menu?.key ?? '').startsWith('/admin')) {
      const loginUser = loginUserStore.loginUser
      if (!loginUser || loginUser.userRole !== 'admin') {
        return false
      }
    }
    return true
  })
}

// 展示在菜单的路由数组
const items = computed(() => filterMenus(originItems))
const userProfileVisible = ref(false)
const userProfileLoading = ref(false)
const userProfile = ref<API.UserVO>({})
const userRoleText = computed(() => {
  return userProfile.value.userRole === 'admin' ? '管理员' : '普通用户'
})

const router = useRouter()
// 当前要高亮的菜单项
const current = ref<string[]>([])
// 监听路由变化，更新高亮菜单项
router.afterEach((to, from, next) => {
  current.value = [to.path]
})

// 路由跳转事件
const doMenuClick = ({ key }: { key: string }) => {
  router.push({
    path: key,
  })
}

// 打开个人信息弹窗并获取当前登录用户信息
const openUserProfile = async () => {
  const userId = loginUserStore.loginUser.id
  if (!userId) {
    message.warning('请先登录')
    return
  }
  userProfileVisible.value = true
  userProfileLoading.value = true
  try {
    const res = await getUserVoByIdUsingGet({
      id: userId,
    })
    if (res.data.code === 0 && res.data.data) {
      userProfile.value = res.data.data
    } else {
      message.error('获取个人信息失败，' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取个人信息失败，' + e.message)
  } finally {
    userProfileLoading.value = false
  }
}

const goToMyProfile = () => {
  const uid = loginUserStore.loginUser.id
  if (uid) router.push('/user/' + uid)
}

// 编辑模式相关
const isEditing = ref(false)
const saving = ref(false)
const avatarUploading = ref(false)
const fileInputRef = ref<HTMLInputElement>()
const editForm = reactive({
  userName: '',
  userProfile: '',
  userAvatar: '',
})

const startEdit = () => {
  editForm.userName = userProfile.value.userName ?? ''
  editForm.userProfile = userProfile.value.userProfile ?? ''
  editForm.userAvatar = userProfile.value.userAvatar ?? ''
  isEditing.value = true
  refreshBindCaptcha()
}

// 绑定邮箱（复用图形码 + 邮箱码组合式函数）
const { captchaImg: bindCaptchaImg, captchaUuid: bindCaptchaUuid, captchaCode: bindCaptchaCode, refreshCaptcha: refreshBindCaptcha } = useImgCaptcha({ immediate: false })
const { countdown: bindCountdown, sendEmailCode: sendBindEmailCode } = useEmailCode('bind')
const bindForm = reactive({
  email: '',
  emailCode: '',
})
const binding = ref(false)

const doSendBindCode = async () => {
  const ok = await sendBindEmailCode(bindForm.email, bindCaptchaUuid.value, bindCaptchaCode.value)
  if (ok) {
    await refreshBindCaptcha()
  }
}

const doBindEmail = async () => {
  if (!bindForm.email || !bindForm.emailCode) {
    message.error('请填写邮箱与邮箱验证码')
    return
  }
  binding.value = true
  try {
    const res = await bindEmailUsingPost({ email: bindForm.email, emailCode: bindForm.emailCode })
    if (res.data.code === 0) {
      message.success('邮箱绑定成功')
      bindForm.email = ''
      bindForm.emailCode = ''
      await loginUserStore.fetchLoginUser()
      if (userProfile.value.id) {
        const profileRes = await getUserVoByIdUsingGet({ id: userProfile.value.id })
        if (profileRes.data.code === 0 && profileRes.data.data) {
          userProfile.value = profileRes.data.data
        }
      }
    } else {
      message.error('绑定失败，' + res.data.message)
      await refreshBindCaptcha()
    }
  } catch (e: any) {
    message.error('绑定失败，' + (e?.message ?? '未知错误'))
  } finally {
    binding.value = false
  }
}

const cancelEdit = () => {
  isEditing.value = false
}

const handleAvatarClick = () => {
  fileInputRef.value?.click()
}

const onAvatarFileChange = async () => {
  const file = fileInputRef.value?.files?.[0]
  if (!file) {
    return
  }
  avatarUploading.value = true
  try {
    const res = await uploadFileUsingPost(file)
    if (res.data.code === 0 && res.data.data) {
      editForm.userAvatar = res.data.data
    } else {
      message.error('头像上传失败，' + res.data.message)
    }
  } catch (e: any) {
    message.error('头像上传失败，' + e.message)
  } finally {
    avatarUploading.value = false
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
  }
}

const handleSave = async () => {
  saving.value = true
  try {
    const res = await editUserUsingPost({
      id: userProfile.value.id,
      userName: editForm.userName,
      userProfile: editForm.userProfile,
      userAvatar: editForm.userAvatar,
    })
    if (res.data.code === 0) {
      message.success('保存成功')
      isEditing.value = false
      // 刷新用户信息
      await loginUserStore.fetchLoginUser()
      // 重新加载弹窗数据
      if (userProfile.value.id) {
        const profileRes = await getUserVoByIdUsingGet({ id: userProfile.value.id })
        if (profileRes.data.code === 0 && profileRes.data.data) {
          userProfile.value = profileRes.data.data
        }
      }
    } else {
      message.error('保存失败，' + res.data.message)
    }
  } catch (e: any) {
    message.error('保存失败，' + e.message)
  } finally {
    saving.value = false
  }
}

// 通知
const notifVisible = ref(false)
const notifLoading = ref(false)
const notifications = ref<any[]>([])
const unreadCount = ref(0)

const fetchUnread = async () => {
  try {
    const res = await getUnreadCountUsingGet()
    if (res.data.code === 0) unreadCount.value = res.data.data ?? 0
  } catch { /* ignore */ }
}

const openNotifications = async () => {
  notifVisible.value = true
  notifLoading.value = true
  unreadCount.value = 0
  clearUnreadUsingPost().catch(() => {})
  try {
    const res = await listNotificationsUsingGet({ current: 1, pageSize: 20 })
    if (res.data.code === 0 && res.data.data) {
      notifications.value = res.data.data.records ?? []
    }
  } finally { notifLoading.value = false }
}

const doClearNotifications = () => {
  Modal.confirm({
    title: '确认清空',
    content: '确定要清空所有通知吗？',
    okText: '确认',
    cancelText: '取消',
    okType: 'danger',
    onOk: async () => {
      try {
        await clearNotificationsUsingPost()
        message.success('已清空')
        notifications.value = []
        unreadCount.value = 0
      } catch { message.error('清空失败') }
    },
  })
}

const doDeleteNotification = async (id: number) => {
  try {
    await deleteNotificationUsingPost(id)
    message.success('已删除')
    notifications.value = notifications.value.filter(n => n.id !== id)
    fetchUnread()
  } catch { message.error('删除失败') }
}

let unreadTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  if (loginUserStore.loginUser.id) {
    fetchUnread()
    unreadTimer = setInterval(fetchUnread, 15000)
  }
})

onBeforeUnmount(() => {
  if (unreadTimer) clearInterval(unreadTimer)
})

// 用户注销
const doLogout = async () => {
  const res = await userLogoutUsingPost()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({
      userName: '未登录',
    })
    message.success('退出登录成功')
    await router.push('/user/login')
  } else {
    message.error('退出登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
#globalHeader {
  height: 64px;
}

/* 绑定邮箱区块 */
.bind-email-hint {
  font-size: 12px;
  color: #999;
  margin-bottom: 8px;
}

.captcha-row {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}

.captcha-row .ant-input {
  flex: 1;
}

.captcha-img {
  height: 32px;
  width: 100px;
  border-radius: 6px;
  border: 1px solid #eee;
  cursor: pointer;
  flex-shrink: 0;
  object-fit: cover;
  background: #fff;
}

.send-btn {
  flex-shrink: 0;
}

#globalHeader .header-row {
  height: 64px;
  align-items: center;
}

#globalHeader .brand-col {
  min-width: 0;
}

#globalHeader .title-bar {
  display: flex;
  align-items: center;
  height: 64px;
  color: #0f172a;
  text-decoration: none;
}

.title {
  font-size: 24px;
  font-weight: 400;
  letter-spacing: 2px;
  margin-left: 16px;
  white-space: nowrap;
  font-family: 'Ma Shan Zheng', 'STXingkai', 'KaiTi', cursive;
  /* 艺术字：琥珀金渐变填充（呼应 logo 花色）+ 轻投影 */
  background: linear-gradient(140deg, #d97706 0%, #b45309 45%, #78350f 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  filter: drop-shadow(0 1px 1px rgba(120, 53, 15, 0.25));
}

.logo {
  height: 42px;
  width: 42px;
  border-radius: 12px;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.14);
}

#globalHeader :deep(.ant-menu) {
  display: flex;
  justify-content: center;
  min-width: 0;
  background: transparent;
  line-height: 64px;
}

#globalHeader :deep(.ant-menu-horizontal) {
  border-bottom: none;
}

#globalHeader :deep(.ant-menu-horizontal > .ant-menu-item) {
  top: 0;
  display: inline-flex;
  align-items: center;
  height: 40px;
  margin: 0 4px;
  padding-inline: 16px;
  border-radius: 999px;
  color: #475569;
  line-height: 40px;
  transition:
    color 0.2s ease,
    background 0.2s ease;
}

#globalHeader :deep(.ant-menu-horizontal > .ant-menu-item::after) {
  display: none;
}

#globalHeader :deep(.ant-menu-horizontal > .ant-menu-item:hover) {
  color: #1677ff;
  background: #eef7ff;
}

#globalHeader :deep(.ant-menu-horizontal > .ant-menu-item-selected) {
  color: #1677ff;
  background: #e6f4ff;
  font-weight: 600;
}

#globalHeader .user-col {
  display: flex;
  justify-content: flex-end;
}

.user-login-status {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  min-width: 0;
}

.user-entry {
  max-width: 128px;
  padding: 4px 8px;
  border: 1px solid #e6f1fb;
  border-radius: 999px;
  background: #ffffff;
  color: #334155;
  font-size: 13px;
  line-height: 20px;
  cursor: pointer;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    background 0.2s ease;
}

.user-entry:hover {
  border-color: #b7dcff;
  background: #f4f9ff;
  box-shadow: 0 8px 20px rgba(37, 99, 235, 0.1);
}

.user-name {
  display: inline-block;
  max-width: 72px;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: middle;
  white-space: nowrap;
}

.user-profile-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.user-profile-hero {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px;
  border-radius: 16px;
  background:
    linear-gradient(135deg, rgba(230, 244, 255, 0.92), rgba(255, 255, 255, 0.96)),
    radial-gradient(circle at 0 0, rgba(22, 119, 255, 0.16), transparent 42%);
}

.user-profile-hero .user-profile-title {
  flex: 1;
  min-width: 0;
}

.avatar-upload-wrapper {
  position: relative;
  border-radius: 50%;
  flex-shrink: 0;
}

.avatar-upload-wrapper.is-editing {
  cursor: pointer;
}

.avatar-overlay {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 12px;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.avatar-upload-wrapper.is-editing:hover .avatar-overlay {
  opacity: 1;
}

.user-profile-edit-fields {
  min-width: 0;
}

.user-profile-name {
  margin-bottom: 8px;
  color: #0f172a;
  font-size: 20px;
  font-weight: 700;
}

.user-profile-actions {
  padding-top: 8px;
}

:deep(.user-profile-modal .ant-modal-body) {
  padding-top: 8px;
}

:deep(.user-profile-modal .ant-descriptions-view) {
  border-color: #e6f1fb;
  border-radius: 12px;
  overflow: hidden;
}

:deep(.user-profile-modal .ant-descriptions-item-label) {
  width: 118px;
  background: #f4f9ff;
  color: #64748b;
  font-weight: 600;
}

.mobile-menu-col {
  display: none;
}

.mobile-menu-icon {
  font-size: 20px;
  cursor: pointer;
  color: #475569;
  line-height: 64px;
}

@media (max-width: 768px) {
  .desktop-nav {
    display: none !important;
  }

  .mobile-menu-col {
    display: flex;
    align-items: center;
    justify-content: center;
  }

  #globalHeader .brand-col {
    flex: 0 0 150px !important;
  }

  #globalHeader .title {
    font-size: 15px;
    margin-left: 10px;
  }

  #globalHeader .nav-col {
    flex: 1;
    min-width: 0;
  }

  #globalHeader .user-col {
    flex: 0 0 auto !important;
  }

  #globalHeader .user-entry {
    max-width: 72px;
    padding-inline: 6px;
  }

  #globalHeader .user-name {
    display: none;
  }
}
</style>
