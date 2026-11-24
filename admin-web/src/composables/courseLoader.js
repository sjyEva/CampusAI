import { getToken } from '../session.js'
import { redirectIfUnauthorized } from '../utils/authError.js'
import { useCourseScope } from './courseScope.js'

// Initial loading and the retry button must handle expired sessions alike.
export function useCourseLoader(router) {
  const scope = useCourseScope()

  async function load() {
    const token = getToken()
    try {
      await scope.load()
    } catch (error) {
      if (!await redirectIfUnauthorized(error, router, token)) throw error
    }
  }

  return { ...scope, load }
}
