<template>
  <div id="userLoginPage" class="auth-page">
    <div class="auth-card">
      <div class="auth-brand animate-fade-in-up">
        <img class="auth-logo" src="../../assets/logo.svg" alt="logo" />
        <span class="auth-sitename">火山图库</span>
      </div>
      <h2 class="auth-title animate-fade-in-up stagger-1">欢迎回来</h2>
      <p class="auth-desc animate-fade-in-up stagger-2">登录后继续管理你的图库</p>
      <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
        <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
          <a-input v-model:value="formState.userAccount" placeholder="请输入账号" size="large" />
        </a-form-item>
        <a-form-item
          name="userPassword"
          :rules="[
            { required: true, message: '请输入密码' },
            { min: 8, message: '密码长度不能小于 8 位' },
          ]"
        >
          <a-input-password v-model:value="formState.userPassword" placeholder="请输入密码" size="large" />
        </a-form-item>
        <a-form-item name="captchaCode" :rules="[{ required: true, message: '请输入验证码' }]">
          <div class="captcha-row">
            <a-input
              v-model:value="formState.captchaCode"
              placeholder="请输入验证码"
              size="large"
              :maxlength="4"
            />
            <img
              v-if="captchaImg"
              :src="captchaImg"
              alt="验证码，点击刷新"
              title="点击刷新"
              class="captcha-img"
              @click="refreshCaptcha"
            />
            <a-button v-else size="large" class="captcha-img captcha-loading" disabled>...</a-button>
          </div>
        </a-form-item>
        <div class="tips">
          <RouterLink to="/user/password/reset">忘记密码？</RouterLink>
          &nbsp;·&nbsp; 没有账号？
          <RouterLink to="/user/register">去注册</RouterLink>
        </div>
        <a-form-item>
          <a-button type="primary" html-type="submit" class="auth-submit">登录</a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>
<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue'
import { userLoginUsingPost } from '@/api/userController.ts'
import { getCaptchaImgUsingGet } from '@/api/authCaptcha.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { message } from 'ant-design-vue'
import router from '@/router' // 用于接受表单输入的值

// 用于接受表单输入的值
const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

// 图形验证码
const captchaImg = ref('')
const captchaUuid = ref('')

const refreshCaptcha = async () => {
  try {
    const res = await getCaptchaImgUsingGet()
    if (res.data.code === 0 && res.data.data) {
      captchaUuid.value = res.data.data.captchaUuid ?? ''
      captchaImg.value = res.data.data.imageBase64 ?? ''
      formState.captchaCode = ''
    }
  } catch (e) {
    // 后端不可达等场景不阻塞页面
    console.error('获取验证码失败', e)
  }
}

onMounted(() => {
  refreshCaptcha()
  // 注册成功跳转过来时，预填账号（读后即清）
  const prefillAccount = sessionStorage.getItem('loginPrefillAccount')
  if (prefillAccount) {
    formState.userAccount = prefillAccount
    sessionStorage.removeItem('loginPrefillAccount')
  }
})

const loginUserStore = useLoginUserStore()

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: any) => {
  const res = await userLoginUsingPost({ ...values, captchaUuid: captchaUuid.value })
  // 登录成功，把登录态保存到全局状态中
  if (res.data.code === 0 && res.data.data) {
    await loginUserStore.fetchLoginUser()
    message.success('登录成功')
    router.push({
      path: '/',
      replace: true,
    })
  } else {
    message.error('登录失败，' + res.data.message)
    // 验证码一次性消费，失败后必须刷新
    await refreshCaptcha()
  }
}
</script>

<style scoped>
/* 居中玻璃卡片 + 页面级柔光斑（蓝/青，透过磨砂呈现） */
.auth-page {
  position: relative;
  min-height: calc(100vh - 160px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 12px;
}

.auth-page::before,
.auth-page::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  filter: blur(64px);
  pointer-events: none;
  z-index: 0;
}

.auth-page::before {
  width: 320px;
  height: 320px;
  top: 4%;
  left: 14%;
  background: var(--grad-ambient-a);
}

.auth-page::after {
  width: 280px;
  height: 280px;
  bottom: 6%;
  right: 10%;
  background: var(--grad-ambient-b);
}

.auth-card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 400px;
  padding: 36px 32px 26px;
  border-radius: 20px;
  background: var(--glass-bg-strong);
  backdrop-filter: blur(var(--glass-blur));
  -webkit-backdrop-filter: blur(var(--glass-blur));
  border: 1px solid var(--glass-border);
  box-shadow: 0 16px 48px rgba(37, 99, 235, 0.12), var(--glass-highlight);
}

/* 品牌区 */
.auth-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-bottom: 16px;
}

.auth-logo {
  height: 40px;
  width: 40px;
  border-radius: 10px;
  border: 2px solid #fff;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.14);
}

.auth-sitename {
  font-family: 'Ma Shan Zheng', 'STXingkai', 'KaiTi', cursive;
  font-size: 26px;
  letter-spacing: 3px;
  /* 与顶栏一致的金色渐变艺术字 */
  background: linear-gradient(140deg, #d97706 0%, #b45309 45%, #78350f 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  filter: drop-shadow(0 1px 1px rgba(120, 53, 15, 0.25));
}

.auth-title {
  text-align: center;
  font-size: 20px;
  font-weight: 600;
  color: var(--app-text);
  margin: 0 0 4px;
}

.auth-desc {
  text-align: center;
  color: var(--app-text-secondary);
  font-size: 13px;
  margin: 0 0 22px;
}

/* 表单项依次入场 */
.auth-card :deep(.ant-form-item) {
  animation: fadeInUp 0.45s cubic-bezier(0.34, 1.56, 0.64, 1) both;
  animation-delay: 120ms;
  margin-bottom: 18px;
}

.auth-card :deep(.ant-form-item:nth-of-type(2)) {
  animation-delay: 190ms;
}

.auth-card :deep(.ant-form-item:nth-of-type(3)) {
  animation-delay: 260ms;
}

.tips {
  color: var(--app-text-secondary);
  text-align: right;
  font-size: 13px;
  margin-bottom: 14px;
}

/* 图形验证码行：输入框 + 可点击刷新的图片 */
.captcha-row {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
}

.captcha-row .ant-input {
  flex: 1;
}

.captcha-img {
  height: 40px;
  width: 120px;
  border-radius: 8px;
  border: 1px solid var(--glass-border);
  cursor: pointer;
  flex-shrink: 0;
  object-fit: cover;
  background: #fff;
}

.captcha-loading {
  color: var(--app-text-secondary);
}

.auth-submit {
  width: 100%;
  height: 40px;
}

@media (max-width: 768px) {
  .auth-page {
    min-height: calc(100vh - 120px);
    padding: 16px 10px;
  }

  .auth-page::before {
    width: 220px;
    height: 220px;
  }

  .auth-page::after {
    width: 190px;
    height: 190px;
  }

  .auth-card {
    padding: 28px 20px 20px;
  }
}
</style>
