import { computed, ref } from 'vue'
import { listCourses } from '../api/courses.js'
import { getToken } from '../session.js'

const courses = ref([])
const selectedId = ref('all')
const loading = ref(false)
const error = ref('')
let activeRequest = null

function cancelLoad() {
  activeRequest?.controller.abort()
  activeRequest = null
}

export function useCourseScope() {
  const options = computed(() => [
    ...courses.value.map((course) => ({ value: String(course.id), label: course.name })),
    { value: 'all', label: '全部负责课程' },
  ])

  const visibleCourses = computed(() => {
    if (selectedId.value === 'all') return courses.value
    return courses.value.filter((course) => String(course.id) === selectedId.value)
  })

  const scopeLabel = computed(() => {
    return options.value.find((option) => option.value === selectedId.value)?.label || '全部负责课程'
  })

  async function load() {
    const token = getToken()
    if (!token) {
      resetCourseScope()
      return
    }
    cancelLoad()
    const request = { controller: new AbortController() }
    activeRequest = request
    const isCurrent = () => activeRequest === request && getToken() === token
    loading.value = true
    error.value = ''
    try {
      const result = await listCourses(token, request.controller.signal)
      if (!isCurrent()) return
      courses.value = Array.isArray(result.data) ? result.data : []
      if (selectedId.value !== 'all' && !courses.value.some((course) => String(course.id) === selectedId.value)) {
        selectedId.value = 'all'
      }
    } catch (err) {
      if (!isCurrent()) return
      courses.value = []
      if (err.code === 401) throw err
      error.value = err.message
    } finally {
      if (activeRequest === request) {
        activeRequest = null
        loading.value = false
      }
    }
  }

  return { courses, visibleCourses, selectedId, options, scopeLabel, loading, error, load }
}

export function resetCourseScope() {
  cancelLoad()
  courses.value = []
  selectedId.value = 'all'
  loading.value = false
  error.value = ''
}
