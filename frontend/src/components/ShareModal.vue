<template>
  <div>
    <Transition name="modal-fade" mode="out-in">
      <a-modal
        v-if="visible"
        v-model:visible="visible"
        :title="title"
        :footer="false"
        @cancel="closeModal"
        class="share-modal"
      >
      <h4>复制分享链接</h4>
      <a-typography-link copyable>
        {{ link }}
      </a-typography-link>
      <div style="margin-bottom: 16px" />
      <h4>手机扫码查看</h4>
        <a-qrcode :value="link" />
      </a-modal>
    </Transition>
  </div>
</template>
<script lang="ts" setup>
import { ref } from 'vue'

interface Props {
  title: string;
  link: string;
}

const props = withDefaults(defineProps<Props>(), {
  title: "分享图片",
  // link: 'https://www.codefather.cn'
  link: ''
})

// 是否可见
const visible = ref(false)

// 打开弹窗
const openModal = () => {
  visible.value = true
}

// 关闭弹窗
const closeModal = () => {
  visible.value = false;
}

// 暴露函数给父组件
defineExpose({
  openModal,
})
</script>

<style scoped>
/* 弹窗进出动画 */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.25s ease;
}
.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}

/* 弹窗内容入场交给 AntD 内置 zoom 过渡，不再叠加自定义动画（避免双触发） */

/* 弹窗内标题交错入场 */
.share-modal :deep(h4) {
  animation: modal-item-in 0.3s ease both;
}
.share-modal :deep(h4:nth-child(2)) {
  animation-delay: 0.1s;
}
.share-modal :deep(.ant-typography) {
  animation: modal-item-in 0.3s ease 0.15s both;
}
.share-modal :deep(.ant-qrcode) {
  animation: modal-item-in 0.3s ease 0.2s both;
}

@keyframes modal-item-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
