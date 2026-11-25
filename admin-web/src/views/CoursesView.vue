<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCourse, listChapters } from '../api/courses.js'
import PageHead from '../components/PageHead.vue'
import { useCourseLoader } from '../composables/courseLoader.js'
import { getToken, profile } from '../session.js'
import { redirectIfUnauthorized } from '../utils/authError.js'
import { toast } from '../utils/toast.js'

const router = useRouter()
const { visibleCourses, loading, error, load } = useCourseLoader(router)
const creating = ref(false)
const chaptersOpen = ref(false)
const chapterLoading = ref(false)
const chapterError = ref('')
const chapterCourse = ref(null)
const chapters = ref([])
let chapterRequest
const form = ref({ name: '', code: '', status: '未发布' })

function ownerLabel() {
  return profile.value?.role === 'teacher' ? profile.value.nickName : '按权限可见'
}

function cancelChapterRequest() {
  chapterRequest?.abort()
  chapterRequest = undefined
  chapterLoading.value = false
}

async function openChapters(course) {
  cancelChapterRequest()
  const request = new AbortController()
  chapterRequest = request
  const token = getToken()
  chaptersOpen.value = true
  chapterCourse.value = null
  chapters.value = []
  chapterError.value = ''
  chapterLoading.value = true
  try {
    const [detail, chapterList] = await Promise.all([
      getCourse(token, course.id, request.signal),
      listChapters(token, course.id, request.signal),
    ])
    if (chapterRequest !== request || token !== getToken()) return
    chapterCourse.value = detail.data
    chapters.value = chapterList.data
  } catch (err) {
    if (chapterRequest !== request || token !== getToken()) return
    if (await redirectIfUnauthorized(err, router, token)) return
    if (chapterRequest === request && token === getToken()) chapterError.value = err.message
  } finally {
    if (chapterRequest === request) cancelChapterRequest()
  }
}

function createCourse() {
  creating.value = false
  form.value = { name: '', code: '', status: '未发布' }
  toast('已创建，默认未发布（示例，尚未写入）')
}
</script>

<template>
  <div class="desk-page">
  <PageHead title="课程与章节" en="Courses" description="未发布课程对学生不可见。教师仅能管理负责课程。">
    <el-button type="primary" @click="creating = true">新建课程</el-button>
  </PageHead>
  <p v-if="loading" class="foot">正在读取课程范围…</p>
  <div v-else-if="error" class="foot">
    <p class="error" role="alert">{{ error }}</p>
    <el-button class="btn-ghost" @click="load">重新加载</el-button>
  </div>
  <div v-else class="sheet">
  <table class="data">
    <thead>
      <tr><th>课程</th><th>编号</th><th>负责人</th><th>章节</th><th>资料</th><th>状态</th><th>操作</th></tr>
    </thead>
    <tbody>
      <tr v-if="visibleCourses.length === 0">
        <td colspan="7">当前账号在此范围内没有可见课程。</td>
      </tr>
      <tr v-for="course in visibleCourses" :key="course.id">
        <td>{{ course.name }}</td>
        <td>{{ course.courseCode }}</td>
        <td>{{ ownerLabel() }}</td>
        <td>—</td>
        <td>—</td>
        <td class="st">待对接</td>
        <td>
          <button class="link" type="button" @click="openChapters(course)">章节</button>
          <button class="link" type="button" @click="toast('打开编辑（示例，尚未写入）')">编辑</button>
        </td>
      </tr>
    </tbody>
  </table>
  </div>
  <p class="foot">课程与章节来自后端。课程写入、章节维护及资料数量留到第 2 周。</p>

  <el-dialog v-model="creating" title="新建课程" width="480px" class="glass-dialog" align-center>
    <p class="dialog-note">创建后默认未发布，学生不可见。本周只演示表单，不提交到后端。</p>
    <div class="field"><label>课程名称</label><el-input v-model="form.name" placeholder="例如：机器学习" /></div>
    <div class="field"><label>课程编号</label><el-input v-model="form.code" placeholder="CS-ML-2026" /></div>
    <div class="field">
      <label>状态</label>
      <el-select v-model="form.status"><el-option label="未发布" value="未发布" /><el-option label="发布" value="发布" /></el-select>
    </div>
    <template #footer>
      <el-button class="btn-ghost" @click="creating = false">取消</el-button>
      <el-button type="primary" @click="createCourse">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="chaptersOpen" @close="cancelChapterRequest" :title="`章节 · ${chapterCourse?.name || '课程'}`" width="480px" class="glass-dialog" align-center>
    <p v-if="chapterLoading" class="dialog-note">正在读取课程章节…</p>
    <p v-else-if="chapterError" class="error" role="alert">{{ chapterError }}</p>
    <template v-else-if="chapterCourse">
      <p class="dialog-note">{{ chapterCourse.courseCode }}。{{ chapterCourse.description || '暂无简介' }}</p>
      <p v-if="chapters.length === 0" class="dialog-note">本课程暂无章节。</p>
      <table v-else>
        <thead><tr><th>顺序</th><th>章节</th></tr></thead>
        <tbody>
          <tr v-for="chapter in chapters" :key="chapter.id">
            <td>{{ chapter.sortOrder }}</td>
            <td>{{ chapter.title }}</td>
          </tr>
        </tbody>
      </table>
    </template>
    <template #footer>
      <el-button class="btn-ghost" @click="chaptersOpen = false">关闭</el-button>
    </template>
  </el-dialog>
  </div>
</template>
