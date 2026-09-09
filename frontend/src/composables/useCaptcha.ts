import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { getCaptchaImgUsingGet, sendEmailCaptchaUsingPost } from '@/api/authCaptcha.ts'

/**
 * 图形验证码：进入页面自动获取，点击图片刷新
 */
export function useImgCaptcha(options?: { immediate?: boolean }) {
  const immediate = options?.immediate ?? true
  const captchaImg = ref('')
  const captchaUuid = ref('')
  const captchaCode = ref('')

  const refreshCaptcha = async () => {
    try {
      const res = await getCaptchaImgUsingGet()
      if (res.data.code === 0 && res.data.data) {
        captchaUuid.value = res.data.data.captchaUuid ?? ''
        captchaImg.value = res.data.data.imageBase64 ?? ''
        captchaCode.value = ''
      }
    } catch (e) {
      // 后端不可达等场景不阻塞页面
      console.error('获取验证码失败', e)
    }
  }

  if (immediate) {
    onMounted(refreshCaptcha)
  }

  return { captchaImg, captchaUuid, captchaCode, refreshCaptcha }
}

/**
 * 邮箱验证码发送与 60 秒倒计时
 * @param scene 使用场景：register / reset / bind
 */
export function useEmailCode(scene: string) {
  const countdown = ref(0)
  let timer: number | undefined

  const sendEmailCode = async (email: string, captchaUuid: string, captchaCode: string) => {
    if (!email) {
      message.error('请先输入邮箱')
      return false
    }
    if (!captchaUuid || !captchaCode) {
      message.error('请先输入图形验证码')
      return false
    }
    const res = await sendEmailCaptchaUsingPost({ scene, email, captchaUuid, captchaCode })
    if (res.data.code === 0) {
      message.success('验证码已发送，请查收邮箱')
      countdown.value = 60
      timer = window.setInterval(() => {
        countdown.value--
        if (countdown.value <= 0 && timer) {
          clearInterval(timer)
          timer = undefined
        }
      }, 1000)
      return true
    }
    message.error(res.data.message ?? '发送失败')
    return false
  }

  return { countdown, sendEmailCode }
}
