import { ElMessage } from 'element-plus'

export function toast(message) {
  ElMessage.closeAll()
  ElMessage({
    message,
    duration: 1600,
    customClass: 'campus-toast',
    offset: 20,
  })
}
