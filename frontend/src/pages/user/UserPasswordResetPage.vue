<template>
  <div id="userPasswordResetPage" class="auth-page">
    <div class="auth-card">
      <div class="auth-brand animate-fade-in-up">
        <img class="auth-logo" src="../../assets/logo.svg" alt="logo" />
        <span class="auth-sitename">火山图库</span>
      </div>
      <h2 class="auth-title animate-fade-in-up stagger-1">找回密码</h2>
      <p class="auth-desc animate-fade-in-up stagger-2">通过注册邮箱验证身份并重置密码</p>
      <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
        <a-form-item name="email" :rules="[{ required: true, message: '请输入注册邮箱' }]">
          <a-input v-model:value="formState.email" placeholder="请输入注册邮箱" size="large" />
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
        <a-form-item
          name="newPassword"
          :rules="[
            { required: true, message: '请输入新密码' },
            { min: 8, message: '密码长度不能小于 8 位' },
          ]"
        >
          <a-input-password v-model:value="formState.newPassword" placeholder="请输入新密码" size="large" />
        </a-form-item>
        <div class="tips">
          想起密码了？
          <RouterLink to="/user/login">去登录</RouterLink>
        </div>
        <a-form-item>
          <a-button type="primary" html-type="submit" class="auth-submit">重置密码</a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>
<script lang="ts" setup>
import { reactive } from 'vue'
import { resetPasswordUsingPost } from '@/api/authCaptcha.ts'
import { useImgCaptcha, useEmailCode } from '@/composables/useCaptcha.ts'
import { message } from 'ant-design-vue'
import router from '@/router'

const formState = reactive<API.UserPasswordResetRequest>({
  email: '',
  emailCode: '',
  newPassword: '',
  captchaCode: '',
})

const { captchaImg, captchaUuid, captchaCode, refreshCaptcha } = useImgCaptcha()
const { countdown, sendEmailCode } = useEmailCode('reset')

const handleSendCode = async () => {
  await sendEmailCode(formState.email ?? '', captchaUuid.value, formState.captchaCode ?? '')
  await refreshCaptcha()
}

const handleSubmit = async (values: any) => {
  const res = await resetPasswordUsingPost(values)
  if (res.data.code === 0) {
    message.success('密码已重置，请使用新密码登录')
    router.push({ path: '/user/login', replace: true })
  } else {
    message.error('重置失败，' + res.data.message)
    await refreshCaptcha()
  }
}
</script>

<style scoped>
/* 与登录/注册页保持一致的玻璃卡片风格 */
.auth-page {
  position: relative;
  min-height: calc(100vh - 160px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 12px;
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
  background: linear-gradient(140deg, #d97706 0%, #b45309 45%, #78350f 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
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

.tips {
  color: var(--app-text-secondary);
  text-align: right;
  font-size: 13px;
  margin-bottom: 14px;
}

.auth-submit {
  width: 100%;
  height: 40px;
}
</style>
