<script setup>
import { ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { getCourse } from '../api/courses.js'
import { clearSession, getToken } from '../session.js'

const route = useRoute()
const router = useRouter()
const course = ref(null)
const loading = ref(true)
const errorMessage = ref('')
const reload = ref(0)

watch([() => route.params.id, reload], async ([id], _, onCleanup) => {
  if (!id) return
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  course.value = null
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await getCourse(getToken(), id, controller.signal)
    if (!controller.signal.aborted) course.value = result.data
  } catch (error) {
    if (controller.signal.aborted) return
    if (error.code === 401) {
      clearSession()
      await router.replace({ name: 'login' })
      return
    }
    errorMessage.value = error.message
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <header class="page-heading">
    <RouterLink class="back-link" :to="{ name: 'courses' }">← 返回课程列表</RouterLink>
    <h1>{{ course?.name || '课程详情' }}</h1>
  </header>
  <section class="panel" aria-label="课程详情" :aria-busy="loading">
    <p v-if="loading" class="status" role="status">正在加载课程…</p>
    <div v-else-if="errorMessage" class="status">
      <p class="error" role="alert">{{ errorMessage }}</p>
      <button type="button" class="secondary-button" @click="reload++">重新加载</button>
    </div>
    <dl v-else-if="course" class="details">
      <div><dt>课程编号</dt><dd class="course-code">{{ course.courseCode }}</dd></div>
      <div><dt>课程简介</dt><dd>{{ course.description || '暂无简介' }}</dd></div>
    </dl>
  </section>
</template>
