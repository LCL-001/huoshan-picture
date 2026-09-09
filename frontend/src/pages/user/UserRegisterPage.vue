<template>
  <div id="userRegisterPage" class="auth-page">
    <div class="auth-card">
      <div class="auth-brand animate-fade-in-up">
        <img class="auth-logo" src="../../assets/logo.svg" alt="logo" />
        <span class="auth-sitename">火山图库</span>
      </div>
      <h2 class="auth-title animate-fade-in-up stagger-1">创建账号</h2>
      <p class="auth-desc animate-fade-in-up stagger-2">注册后开启你的图库之旅</p>
      <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
        <a-form-item name="email" :rules="[{ required: true, message: '请输入邮箱' }]">
          <a-input v-model:value="formState.email" placeholder="请输入邮箱" size="large" />
        </a-form-item>
        <a-form-item name="captchaCode" :rules="[{ required: true, message: '请输入图形验证码' }]">
          <div class="captcha-row">
            <a-input v-model:value="formState.captchaCode" placeholder="请输入图形验证码" size="large" :maxlength="4" />
            <img
              v-if="captchaImg"
              :src="captchaImg"
              alt="验证码，点击刷新"
              title="点击刷新"
              class="captcha-img"
              @click="refreshCaptcha"
            />
          </div>
        </a-form-item>
        <a-form-item name="emailCode" :rules="[{ required: true, message: '请输入邮箱验证码' }]">
          <div class="captcha-row">
            <a-input v-model:value="formState.emailCode" placeholder="请输入邮箱验证码" size="large" :maxlength="6" />
            <a-button size="large" class="send-btn" :disabled="countdown > 0" @click="handleSendCode">
              {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
            </a-button>
          </div>
        </a-form-item>
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
        <a-form-item
          name="checkPassword"
          :rules="[
            { required: true, message: '请输入确认密码' },
            { min: 8, message: '确认密码长度不能小于 8 位' },
          ]"
        >
          <a-input-password v-model:value="formState.checkPassword" placeholder="请输入确认密码" size="large" />
        </a-form-item>
        <div class="tips">
          已有账号？
          <RouterLink to="/user/login">去登录</RouterLink>
        </div>
        <a-form-item>
          <a-button type="primary" html-type="submit" class="auth-submit">注册</a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>
<script lang="ts" setup>
import { reactive } from 'vue'
import { userRegisterUsingPost } from '@/api/userController.ts'
import { useImgCaptcha, useEmailCode } from '@/composables/useCaptcha.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { message } from 'ant-design-vue'
import router from '@/router' // 用于接受表单输入的值

// 用于接受表单输入的值
const formState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
  email: '',
  emailCode: '',
  captchaCode: '',
})

// 图形验证码与邮箱验证码
const { captchaImg, captchaUuid, captchaCode, refreshCaptcha } = useImgCaptcha()
const { countdown, sendEmailCode } = useEmailCode('register')

const handleSendCode = async () => {
  await sendEmailCode(formState.email ?? '', captchaUuid.value, formState.captchaCode ?? '')
  await refreshCaptcha()
}

const loginUserStore = useLoginUserStore()

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: any) => {
  // 校验两次输入的密码是否一致
  if (values.userPassword !== values.checkPassword) {
    message.error('两次输入的密码不一致')
    return
  }
  const res = await userRegisterUsingPost({ ...values, captchaUuid: captchaUuid.value })
  // 注册成功，跳转到登录页面
  if (res.data.code === 0 && res.data.data) {
    message.success('注册成功，请登录')
    // 预填刚注册的账号，登录页只需补密码与验证码（sessionStorage 用后即清）
    sessionStorage.setItem('loginPrefillAccount', values.userAccount ?? '')
    router.push({
      path: '/user/login',
      replace: true,
    })
  } else {
    message.error('注册失败，' + res.data.message)
    // 图形验证码一次性消费，失败后必须刷新
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

.auth-card :deep(.ant-form-item:nth-of-type(4)) {
  animation-delay: 330ms;
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

.send-btn {
  flex-shrink: 0;
  height: 40px;
  min-width: 108px;
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
