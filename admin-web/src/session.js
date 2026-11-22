import { shallowRef } from 'vue'
import { getInfo, login, logout } from './api/login.js'
import { getRouters } from './api/menu.js'
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

function findCourseMenu(routes) {
  for (const route of routes) {
    if (route.component === 'campusai/courses/index' && !route.hidden) return route
    if (route.hidden || !route.children) continue
    const child = findCourseMenu(route.children)
    if (child) return child
  }
  return null
}

async function readProfile(token) {
  const { user, roles } = await getInfo(token)
  const role = ['admin', 'teacher'].find((availableRole) => roles.includes(availableRole))
  if (!role) throw new ApiError('管理后台仅对教师和管理员开放。', 403)

  const { data: routes } = await getRouters(token)
  const courseMenu = findCourseMenu(routes ?? [])
  if (!courseMenu) throw new ApiError('当前账号尚未配置校园课程菜单。', 403)
  return { ...user, role, courseMenuTitle: courseMenu.meta?.title || '校园课程' }
}

export async function signIn(username, password, code, uuid) {
  const { token } = await login(username.trim(), password, code, uuid)
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
