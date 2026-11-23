<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCourse } from '../api/courses.js'
import PageHead from '../components/PageHead.vue'
import { useCourseScope } from '../composables/courseScope.js'
import { sampleChapters } from '../mock/sample.js'
import { getToken, profile } from '../session.js'
import { redirectIfUnauthorized } from '../utils/authError.js'
import { toast } from '../utils/toast.js'

const router = useRouter()
const { visibleCourses, loading, error, load } = useCourseScope()
const creating = ref(false)
const chaptersOpen = ref(false)
const chapterLoading = ref(false)
const chapterError = ref('')
const chapterCourse = ref(null)
const form = ref({ name: '', code: '', status: '未发布' })

function ownerLabel() {
  return profile.value?.role === 'teacher' ? profile.value.nickName : '按权限可见'
}

async function openChapters(course) {
  chaptersOpen.value = true
  chapterCourse.value = null
  chapterError.value = ''
  chapterLoading.value = true
  try {
    const result = await getCourse(getToken(), course.id)
    chapterCourse.value = result.data
  } catch (err) {
    if (await redirectIfUnauthorized(err, router)) return
    chapterError.value = err.message
  } finally {
    chapterLoading.value = false
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
  <p class="foot">课程名称与编号来自课程权限接口。章节、资料数量和发布写入留到第 2 周，当前状态为待对接。</p>

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

  <el-dialog v-model="chaptersOpen" :title="`章节 · ${chapterCourse?.name || '课程'}`" width="480px" class="glass-dialog" align-center>
    <p v-if="chapterLoading" class="dialog-note">正在读取课程…</p>
    <p v-else-if="chapterError" class="error" role="alert">{{ chapterError }}</p>
    <template v-else-if="chapterCourse">
      <p class="dialog-note">{{ chapterCourse.courseCode }}。{{ chapterCourse.description || '暂无简介' }}</p>
      <table>
        <thead><tr><th>章节</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="chapter in sampleChapters" :key="chapter.title">
            <td>{{ chapter.title }}</td>
            <td :class="chapter.ok ? 'st-ok' : 'st'">{{ chapter.status }}</td>
          </tr>
        </tbody>
      </table>
      <p class="foot">章节列表是示例。上面的课程信息来自权限接口，跨课程读取会被拒绝。</p>
    </template>
    <template #footer>
      <el-button class="btn-ghost" @click="chaptersOpen = false">关闭</el-button>
      <el-button type="primary" @click="chaptersOpen = false; toast('已保存章节（示例，尚未写入）')">保存</el-button>
    </template>
  </el-dialog>
  </div>
</template>
