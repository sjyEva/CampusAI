import { resetCourseScope } from '../composables/courseScope.js'
import { clearSession, getToken } from '../session.js'

export async function redirectIfUnauthorized(error, router, requestToken) {
  if (error?.code !== 401) return false
  // An old request must never sign out the account that logged in after it.
  if (requestToken !== getToken()) return true
  clearSession()
  resetCourseScope()
  await router.replace({ name: 'login' })
  return true
}
