import { getInfo, logout } from './login.js'
import { getToken, removeToken } from '../utils/auth.js'

const demoSession = { mode: 'demo', owner: 'demo', nickname: '同学' }
let checkedToken
let pending

export function studentSession() {
  const token = getToken()
  if (!token) return Promise.resolve(demoSession)
  if (token !== checkedToken) {
    checkedToken = token
    pending = getInfo().then(result => {
      if (token !== getToken()) throw new Error('登录状态已变化，请重试')
      if (!result.roles?.includes('student') || result.user?.userId == null) {
        throw new Error('请使用学生账号连接后端')
      }
      return {
        mode: 'live', owner: 'student:' + result.user.userId, token,
        nickname: result.user.nickName || result.user.userName || '同学'
      }
    }).catch(error => {
      if (checkedToken === token) checkedToken = undefined
      throw error
    })
  }
  return pending
}

export async function signOut() {
  try {
    if (getToken()) await logout()
  } catch {
    // The local session can still be cleared when the service is unavailable.
  } finally {
    checkedToken = undefined
    pending = undefined
    removeToken()
  }
}
