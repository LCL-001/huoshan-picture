import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import Antd from 'ant-design-vue'
import VueCropper from 'vue-cropper';
import 'vue-cropper/dist/index.css'
import 'ant-design-vue/dist/reset.css'
import '@/assets/styles/global.css'
import '@/assets/styles/animations.css'
import '@/access.ts'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(Antd)
app.use(VueCropper)

// 全局涟漪点击指令
app.directive('ripple', {
  mounted(el: HTMLElement) {
    el.addEventListener('click', (e: MouseEvent) => {
      const rect = el.getBoundingClientRect()
      const size = Math.max(rect.width, rect.height) * 2
      const x = e.clientX - rect.left - size / 2
      const y = e.clientY - rect.top - size / 2

      el.style.position = el.style.position || 'relative'
      el.style.overflow = 'hidden'

      const ripple = document.createElement('span')
      ripple.className = 'global-ripple'
      ripple.style.width = size + 'px'
      ripple.style.height = size + 'px'
      ripple.style.left = x + 'px'
      ripple.style.top = y + 'px'

      el.appendChild(ripple)
      setTimeout(() => ripple.remove(), 600)
    })
  },
})

app.mount('#app')
