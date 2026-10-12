/**
 * vue-cropper 1.1.4 的 `typings` 指向包内源码 `lib/index.ts`，后者又 `import './vue-cropper.vue'`；
 * TS 不认 `.vue` 后缀 → TS7016。这里只补一条**窄通配**声明（只覆盖 vue-cropper 自己的 .vue），
 * 刻意不用 `declare module '*.vue'`——那会把本仓所有组件的 props 类型一并降级为 any。
 */
declare module '*/vue-cropper.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent
  export default component
}
