import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import { ApiError } from '../src/api/request.js'
import { useCourseLoader } from '../src/composables/courseLoader.js'
import { resetCourseScope, useCourseScope } from '../src/composables/courseScope.js'
import { clearSession, getToken, profile } from '../src/session.js'
import { redirectIfUnauthorized } from '../src/utils/authError.js'

const tokenKey = 'campusai-admin-token'
const originalStorage = globalThis.sessionStorage
const teacherCourses = [{ id: 1, courseCode: 'CS101', name: 'Teacher course' }]
const adminCourses = [...teacherCourses, { id: 3, courseCode: 'OTHER', name: 'Other teacher course' }]
let navigations
let router
let scope

beforeEach(() => {
  const storage = new Map()
  globalThis.sessionStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: (key) => storage.delete(key),
  }
  resetCourseScope()
  clearSession()
  navigations = []
  router = { replace: async (route) => { navigations.push(route) } }
  scope = useCourseLoader(router)
})

afterEach(() => {
  resetCourseScope()
  clearSession()
  if (originalStorage === undefined) delete globalThis.sessionStorage
  else globalThis.sessionStorage = originalStorage
})

function authenticate(role, token = `${role}-session`) {
  sessionStorage.setItem(tokenKey, token)
  profile.value = { role, nickName: role }
}

function reply(code, data, status = 200) {
  return new Response(JSON.stringify({ code, data, msg: `API ${code}` }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function deferredRequests(t) {
  const requests = []
  // Deliberately let responses finish after abort, as parsing/already queued work
  // can still complete. Correctness must not depend on cancellation alone.
  t.mock.method(globalThis, 'fetch', (url, options) => new Promise((resolve, reject) => {
    requests.push({ url, options, resolve, reject })
  }))
  return requests
}

function assertTeacherSession() {
  assert.equal(getToken(), 'teacher-session')
  assert.equal(profile.value.role, 'teacher')
  assert.deepEqual(navigations, [])
}

test('switching accounts: a late admin response cannot overwrite teacher courses', async (t) => {
  const requests = deferredRequests(t)
  authenticate('admin')
  const oldLoad = scope.load()
  clearSession()
  resetCourseScope()
  authenticate('teacher')
  const newLoad = scope.load()
  requests[1].resolve(reply(200, teacherCourses))
  await newLoad
  scope.selectedId.value = '1'
  requests[0].resolve(reply(200, adminCourses))
  await oldLoad

  assertTeacherSession()
  assert.deepEqual(scope.courses.value, teacherCourses)
  assert.equal(scope.selectedId.value, '1')
  assert.equal(scope.loading.value, false)
  assert.equal(scope.error.value, '')
  assert.equal(requests[0].options.signal.aborted, true)
})

for (const kind of ['business 401', 'HTTP 401', 'network error']) {
  test(`switching accounts: a late ${kind} cannot clear the new session or its data`, async (t) => {
    const requests = deferredRequests(t)
    authenticate('admin')
    const oldLoad = scope.load()
    clearSession()
    resetCourseScope()
    authenticate('teacher')
    const newLoad = scope.load()
    requests[1].resolve(reply(200, teacherCourses))
    await newLoad
    if (kind === 'network error') requests[0].reject(new TypeError('offline'))
    else requests[0].resolve(reply(401, undefined, kind === 'HTTP 401' ? 401 : 200))
    await oldLoad

    assertTeacherSession()
    assert.deepEqual(scope.courses.value, teacherCourses)
    assert.equal(scope.error.value, '')
  })
}

test('logout invalidates pending work even before another account logs in', async (t) => {
  const requests = deferredRequests(t)
  authenticate('admin')
  const pendingLoad = scope.load()
  resetCourseScope()
  clearSession()
  requests[0].resolve(reply(200, adminCourses))
  await pendingLoad

  assert.equal(getToken(), null)
  assert.deepEqual(scope.courses.value, [])
  assert.equal(scope.loading.value, false)
  assert.equal(scope.error.value, '')
})

test('a changed token invalidates a response even without an explicit scope reset', async (t) => {
  const requests = deferredRequests(t)
  authenticate('admin')
  const oldLoad = scope.load()
  authenticate('teacher')
  requests[0].resolve(reply(200, adminCourses))
  await oldLoad

  assertTeacherSession()
  assert.deepEqual(scope.courses.value, [])
})

test('an older request cannot stop the spinner or report an error for a newer load', async (t) => {
  const requests = deferredRequests(t)
  authenticate('teacher')
  const oldLoad = scope.load()
  const newLoad = scope.load()
  requests[0].resolve(reply(401))
  await oldLoad
  assert.equal(scope.loading.value, true)
  assert.equal(scope.error.value, '')
  assertTeacherSession()
  requests[1].resolve(reply(200, teacherCourses))
  await newLoad
  assert.deepEqual(scope.courses.value, teacherCourses)
  assert.equal(scope.loading.value, false)
})

test('a 401 delivered to its handler after an account switch cannot revoke the newer session', async () => {
  authenticate('teacher')
  scope.courses.value = teacherCourses
  const handled = await redirectIfUnauthorized(new ApiError('Expired', 401), router, 'admin-session')
  assert.equal(handled, true)
  assertTeacherSession()
  assert.deepEqual(scope.courses.value, teacherCourses)
})

for (const status of [200, 401]) {
  test(`retry after a network failure: expired session (HTTP ${status}) clears state and returns to login`, async (t) => {
    authenticate('teacher')
    let attempts = 0
    t.mock.method(globalThis, 'fetch', async () => {
      if (++attempts === 1) throw new TypeError('offline')
      return reply(401, undefined, status)
    })
    await scope.load()
    assert.match(scope.error.value, /无法连接/)
    assertTeacherSession()
    scope.selectedId.value = '1'

    // This is the same load() returned to both AdminLayout and CoursesView.
    await assert.doesNotReject(scope.load())
    assert.equal(getToken(), null)
    assert.equal(profile.value, null)
    assert.deepEqual(navigations, [{ name: 'login' }])
    assert.deepEqual(scope.courses.value, [])
    assert.equal(scope.selectedId.value, 'all')
    assert.equal(scope.error.value, '')
    assert.equal(scope.loading.value, false)
  })
}

test('a retry with a valid session loads courses normally', async (t) => {
  authenticate('teacher')
  let attempts = 0
  t.mock.method(globalThis, 'fetch', async () => {
    if (++attempts === 1) throw new TypeError('offline')
    return reply(200, teacherCourses)
  })
  await scope.load()
  assert.ok(scope.error.value)
  await scope.load()
  assertTeacherSession()
  assert.deepEqual(scope.visibleCourses.value, teacherCourses)
  assert.equal(scope.loading.value, false)
  assert.equal(scope.error.value, '')
})

test('non-authentication server errors keep the current session and remain retryable', async (t) => {
  authenticate('teacher')
  t.mock.method(globalThis, 'fetch', async () => reply(503, undefined, 503))
  await scope.load()
  assertTeacherSession()
  assert.equal(scope.error.value, 'API 503')
  assert.equal(scope.loading.value, false)
})

test('loading without a token clears stale scope without making an API request', async (t) => {
  scope.courses.value = adminCourses
  const fetch = t.mock.method(globalThis, 'fetch', async () => reply(200, adminCourses))
  await scope.load()
  assert.deepEqual(useCourseScope().courses.value, [])
  assert.equal(fetch.mock.callCount(), 0)
})
