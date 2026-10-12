import request from '@/request'

/** getCaptchaImg GET /api/user/captcha/img */
export async function getCaptchaImgUsingGet() {
  return request<API.BaseResponseCaptchaVO_>('/api/user/captcha/img', {
    method: 'GET',
  })
}

/** sendEmailCaptcha POST /api/user/captcha/email（scene: register / reset / bind） */
export async function sendEmailCaptchaUsingPost(body: API.EmailCaptchaRequest) {
  return request<API.BaseResponseBoolean_>('/api/user/captcha/email', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}

/** resetPassword POST /api/user/password/reset（忘记密码） */
export async function resetPasswordUsingPost(body: API.UserPasswordResetRequest) {
  return request<API.BaseResponseBoolean_>('/api/user/password/reset', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}

/** bindEmail POST /api/user/email/bind（登录态绑定/换绑邮箱） */
export async function bindEmailUsingPost(body: API.UserEmailBindRequest) {
  return request<API.BaseResponseBoolean_>('/api/user/email/bind', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}
