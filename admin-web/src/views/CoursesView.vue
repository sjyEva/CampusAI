<script setup>
import { computed, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { listCourses } from '../api/courses.js'
import { clearSession, getToken, profile } from '../session.js'

const router = useRouter()
const title = computed(() => profile.value?.role === 'admin' ? '全部课程' : '负责课程')
const courses = ref([])
const loading = ref(true)
const errorMessage = ref('')
const reload = ref(0)

watch(reload, async (_, __, onCleanup) => {
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listCourses(getToken(), controller.signal)
    if (!controller.signal.aborted) courses.value = result.data
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
    <h1>{{ title }}</h1>
    <p v-if="!loading && !errorMessage">{{ courses.length }} 门可见课程</p>
  </header>
  <section class="panel" aria-label="课程列表" :aria-busy="loading">
    <p v-if="loading" class="status" role="status">正在加载课程…</p>
    <div v-else-if="errorMessage" class="status">
      <p class="error" role="alert">{{ errorMessage }}</p>
      <button type="button" class="secondary-button" @click="reload++">重新加载</button>
    </div>
    <p v-else-if="courses.length === 0" class="status" role="status">当前账号没有可见课程。</p>
    <table v-else class="course-table">
      <thead>
        <tr><th scope="col">课程编号</th><th scope="col">课程名称</th><th scope="col">课程简介</th></tr>
      </thead>
      <tbody>
        <tr v-for="course in courses" :key="course.id">
          <td class="course-code">{{ course.courseCode }}</td>
          <td><RouterLink :to="{ name: 'course', params: { id: course.id } }">{{ course.name }}</RouterLink></td>
          <td>{{ course.description || '暂无简介' }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
