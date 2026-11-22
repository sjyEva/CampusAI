import { shallowRef } from 'vue'
import { getInfo, login, logout } from './api/login.js'
import { ApiError } from './api/request.js'

const tokenKey = 'campusai-admin-token'
export const profile = shallowRef(null)

export function getToken() {
  return sessionStorage.getItem(tokenKey)
}

export function clearSession() {
  sessionStorage.removeItem(tokenKey)
  profile.value = null
}

async function readProfile(token) {
  const { user, roles } = await getInfo(token)
  const role = ['admin', 'teacher'].find((availableRole) => roles.includes(availableRole))
  if (!role) throw new ApiError('管理后台仅对教师和管理员开放。', 403)
  return { ...user, role }
}

export async function signIn(username, password) {
  const { token } = await login(username.trim(), password)
  try {
    const nextProfile = await readProfile(token)
    sessionStorage.setItem(tokenKey, token)
    profile.value = nextProfile
  } catch (error) {
    await logout(token).catch(() => {})
    throw error
  }
}

export async function restoreSession() {
  const token = getToken()
  if (!token || profile.value) return Boolean(profile.value)
  try {
    profile.value = await readProfile(token)
    return true
  } catch {
    clearSession()
    return false
  }
}

export async function signOut() {
  const token = getToken()
  try {
    if (token) await logout(token)
  } finally {
    clearSession()
  }
}
