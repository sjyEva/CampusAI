import { resetCourseScope } from '../composables/courseScope.js'
import { clearSession } from '../session.js'

export async function redirectIfUnauthorized(error, router) {
  if (error?.code !== 401) return false
  clearSession()
  resetCourseScope()
  await router.replace({ name: 'login' })
  return true
}
