import request from '@/request'

/** getCaptchaImg GET /api/user/captcha/img */
export async function getCaptchaImgUsingGet(options?: { [key: string]: any }) {
  return request<API.BaseResponseCaptchaVO_>('/api/user/captcha/img', {
    method: 'GET',
    ...(options || {}),
  })
}

/** sendEmailCaptcha POST /api/user/captcha/email（scene: register / reset / bind） */
export async function sendEmailCaptchaUsingPost(
  body: API.EmailCaptchaRequest,
  options?: { [key: string]: any },
) {
  return request<API.BaseResponseBoolean_>('/api/user/captcha/email', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** resetPassword POST /api/user/password/reset（忘记密码） */
export async function resetPasswordUsingPost(
  body: API.UserPasswordResetRequest,
  options?: { [key: string]: any },
) {
  return request<API.BaseResponseBoolean_>('/api/user/password/reset', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** bindEmail POST /api/user/email/bind（登录态绑定/换绑邮箱） */
export async function bindEmailUsingPost(
  body: API.UserEmailBindRequest,
  options?: { [key: string]: any },
) {
  return request<API.BaseResponseBoolean_>('/api/user/email/bind', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
