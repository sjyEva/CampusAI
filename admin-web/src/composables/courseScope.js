import { computed, ref } from 'vue'
import { listCourses } from '../api/courses.js'
import { getToken } from '../session.js'

const courses = ref([])
const selectedId = ref('all')
const loading = ref(false)
const error = ref('')

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
    if (!token) return
    loading.value = true
    error.value = ''
    try {
      const result = await listCourses(token)
      courses.value = Array.isArray(result.data) ? result.data : []
      if (selectedId.value !== 'all' && !courses.value.some((course) => String(course.id) === selectedId.value)) {
        selectedId.value = 'all'
      }
    } catch (err) {
      courses.value = []
      if (err.code === 401) throw err
      error.value = err.message
    } finally {
      loading.value = false
    }
  }

  return { courses, visibleCourses, selectedId, options, scopeLabel, loading, error, load }
}

export function resetCourseScope() {
  courses.value = []
  selectedId.value = 'all'
  loading.value = false
  error.value = ''
}
