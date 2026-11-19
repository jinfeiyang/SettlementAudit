import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

const MIN_HEIGHT = 320

/**
 * 表格高度按可视窗口动态计算：表格顶部到底部预留区（分页条、页脚）的剩余空间。
 * 固定 max-height 小屏会出现双重滚动条、大屏会留一大片空白。
 * 同时监听窗口缩放和宿主容器显隐（Tab 切换），避免隐藏时测到 0 高度。
 */
export function useTableHeight(tableRef: Ref<any>, bottomGap = 96) {
  const height = ref(MIN_HEIGHT)

  function update() {
    const el = tableRef.value?.$el as HTMLElement | undefined
    if (!el) {
      return
    }
    const rect = el.getBoundingClientRect()
    // 隐藏的 Tab 面板宽高均为 0，跳过，等显示后由 ResizeObserver 再算
    if (rect.width === 0 && rect.height === 0) {
      return
    }
    height.value = Math.max(MIN_HEIGHT, Math.floor(window.innerHeight - rect.top - bottomGap))
  }

  let observer: ResizeObserver | undefined

  onMounted(() => {
    update()
    window.addEventListener('resize', update)
    const host = tableRef.value?.$el?.parentElement as HTMLElement | undefined
    if (host && typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(update)
      observer.observe(host)
    }
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', update)
    observer?.disconnect()
  })

  return height
}
