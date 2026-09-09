import { onBeforeUnmount, onMounted } from 'vue'

/**
 * 滚动显现：给匹配 selector 的元素（需带 .observe-animate 类）进入视口后加 .animate-visible，
 * 过渡样式由 animations.css 提供。一次性显现，显现后不再观察。
 *
 * 列表数据异步渲染时，在数据更新后调用返回的 observeAll() 重新收集新元素。
 */
export function useAnimateOnScroll(
  selector = '.observe-animate',
  options?: IntersectionObserverInit
) {
  let observer: IntersectionObserver | null = null

  /** （重新）收集 root 范围内所有匹配元素加入观察，已显现的元素自动跳过 */
  const observeAll = (root: ParentNode = document) => {
    if (!observer) return
    root.querySelectorAll(selector).forEach((el) => {
      if (!el.classList.contains('animate-visible')) {
        observer!.observe(el)
      }
    })
  }

  onMounted(() => {
    observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add('animate-visible')
            observer?.unobserve(entry.target)
          }
        })
      },
      { threshold: 0.1, rootMargin: '0px 0px -40px 0px', ...options }
    )
    observeAll()
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
  })

  return { observeAll }
}
