import { materialUrl } from '../api/study.js'

export function tell(error) {
  const title = typeof error === 'string' ? error : error?.message || '操作未完成，请重试'
  if (title === '已取消选择') return
  uni.showToast({ title, icon: 'none' })
}

export function navigate(url) {
  uni.navigateTo({ url, fail: () => tell('无法打开页面，请返回后再试') })
}

export function readEntry(entry, chunkId) {
  navigate(materialUrl(entry, chunkId))
}

export function editEntry(entry) {
  navigate('/pages/library/edit?id=' + encodeURIComponent(entry.id))
}

export function newEntry(kind, courseId) {
  navigate('/pages/library/edit?kind=' + kind + (courseId ? '&courseId=' + encodeURIComponent(courseId) : ''))
}

export function navigationMetrics() {
  // #ifdef MP-WEIXIN
  let status = 20
  let rowHeight = 46
  try {
    const info = typeof wx.getWindowInfo === 'function' ? wx.getWindowInfo() : wx.getSystemInfoSync()
    status = info?.statusBarHeight ?? status
    const capsule = wx.getMenuButtonBoundingClientRect?.()
    if (capsule?.height) rowHeight = Math.max(rowHeight, capsule.bottom - status + Math.max(4, capsule.top - status))
  } catch {
    // Keep the device-safe defaults when the simulator omits window metrics.
  }
  return { status, rowHeight }
  // #endif
  // #ifndef MP-WEIXIN
  return { status: 0, rowHeight: 46 }
  // #endif
}
