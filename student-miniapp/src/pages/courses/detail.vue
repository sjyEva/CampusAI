<template>
  <StudyHeader back title="课程详情" :mode="session?.mode" :profile="workspace?.profile" @profile="drawerOpen = !!workspace" />
  <view class="study-page study-detail with-assistant">
    <view v-if="loading" class="study-note">正在加载课程…</view>
    <view v-else-if="loadError" class="study-section">
      <view class="study-error">{{ loadError }}</view>
      <button v-if="courseId" class="study-btn" @tap="load">重新加载</button>
    </view>
    <template v-else-if="course && workspace">
      <view class="course-head">
        <view class="study-mark" />
        <view class="course-hero">
          <view class="study-caption code">{{ course.courseCode }}</view>
          <view class="course-title">{{ course.name }}</view>
          <view v-if="course.teacher" class="study-small">{{ course.teacher }}</view>
        </view>
      </view>
      <view class="tabs" aria-label="课程功能">
        <button v-for="item in tabs" :key="item.id" class="tab" :class="{ active: tab === item.id }" @tap="tab = item.id">{{ item.name }}</button>
      </view>
      <template v-if="tab === 'materials'">
        <view class="study-section">
          <view class="panel-title"><text class="study-heading">个人资料与笔记</text><text class="study-tag">仅本机</text></view>
          <view class="study-actions">
            <button class="study-btn" @tap="newEntry('file', course.id)">导入资料</button>
            <button class="study-btn" @tap="newEntry('note', course.id)">写笔记</button>
          </view>
          <view v-if="personal.length" class="personal-list">
            <MaterialList :items="personal" :favorites="workspace.favorites" @favorite="favorite" />
          </view>
          <view v-else class="study-note">暂无个人资料或笔记</view>
        </view>
        <view class="study-section">
          <view class="panel-title"><text class="study-heading">课程资料</text><text class="study-tag">{{ session.mode === 'live' ? '后端已发布' : '演示数据' }}</text></view>
          <view v-if="materialsPending" class="study-note">正在读取资料…</view>
          <view v-else-if="materialsError">
            <view class="study-error">{{ materialsError }}</view>
            <button class="study-btn" @tap="loadMaterials">重新加载资料</button>
          </view>
          <MaterialList v-else-if="materials.length" :items="materials" :favorites="workspace.favorites" @favorite="favorite" />
          <view v-else class="study-note">暂无发布资料</view>
        </view>
        <view class="study-section">
          <view class="panel-title"><text class="study-heading">课程简介</text></view>
          <view class="study-text">{{ course.description || '暂无课程简介' }}</view>
        </view>
      </template>
      <view v-else class="study-section study-stack">
        <view class="panel-title"><text class="study-heading">教师反馈</text><text class="study-tag">本机演示</text></view>
        <view class="study-caption">反馈保存于本机，可在个人中心查看。</view>
        <picker :range="feedbackKinds" :value="kindIndex" @change="kindIndex = Number($event.detail.value)">
          <view class="study-field study-picker"><text>反馈分类</text><text>{{ feedbackKinds[kindIndex] }} ›</text></view>
        </picker>
        <textarea v-model="feedback" class="study-field study-textarea" maxlength="500" aria-label="反馈内容" placeholder="描述你的问题或建议" />
        <view v-if="feedbackError" class="study-error">{{ feedbackError }}</view>
        <button class="study-btn is-primary submit" @tap="sendFeedback">提交反馈</button>
        <view v-for="item in feedbackRows" :key="item.id" class="feedback-row">
          <view class="study-caption">{{ item.kind }} · {{ localDate(item.createdAt) }}</view>
          <view class="study-text">{{ item.text }}</view>
        </view>
      </view>
    </template>
  </view>
  <CourseAssistant v-if="course && session && !loadError" :session="session" :course="course" :start-open="startOpen" />
  <StudentDrawer v-if="drawerOpen && workspace && session" :workspace="workspace" :session="session"
    @close="drawerOpen = false" @change="workspace = $event" />
</template>

<script setup>
import { computed, ref, shallowRef } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import StudyHeader from '../../components/StudyHeader.vue'
import StudentDrawer from '../../components/StudentDrawer.vue'
import CourseAssistant from '../../components/CourseAssistant.vue'
import MaterialList from '../../components/MaterialList.vue'
import { studentSession } from '../../api/session.js'
import { studyCourse, studyMaterials } from '../../api/study.js'
import { feedbackKinds, getWorkspace, localDate, setFavorite, submitFeedback } from '../../api/workspace.js'
import { newEntry, tell } from '../../utils/study-ui.js'

const tabs = [{ id: 'materials', name: '课程资料' }, { id: 'feedback', name: '教师反馈' }]
const courseId = ref('')
const course = ref(null)
const session = shallowRef(null)
const workspace = ref(null)
const tab = ref('materials')
const loading = ref(false)
const loadError = ref('')
const materials = ref([])
const materialsPending = ref(false)
const materialsError = ref('')
const drawerOpen = ref(false)
const startOpen = ref(false)
const kindIndex = ref(0)
const feedback = ref('')
const feedbackError = ref('')
const personal = computed(() => workspace.value?.library.filter(item => item.courseId === courseId.value) || [])
const feedbackRows = computed(() => workspace.value?.feedback.filter(item => item.courseId === courseId.value).slice(0, 4) || [])

onLoad(options => {
  if (!/^[1-9]\d*$/.test(options?.id || '')) {
    loadError.value = '课程编号无效'
    return
  }
  courseId.value = options.id
  startOpen.value = options.ask === '1'
  load()
})

onShow(() => { if (session.value) workspace.value = getWorkspace(session.value) })

async function load() {
  if (loading.value) return
  loadError.value = ''
  loading.value = true
  try {
    session.value = await studentSession()
    workspace.value = getWorkspace(session.value)
    course.value = await studyCourse(session.value, courseId.value)
    loadMaterials()
  } catch (error) {
    loadError.value = error.message
  } finally {
    loading.value = false
  }
}

async function loadMaterials() {
  if (materialsPending.value) return
  materialsPending.value = true
  materialsError.value = ''
  try { materials.value = await studyMaterials(session.value, course.value) }
  catch (error) { materialsError.value = error.message }
  finally { materialsPending.value = false }
}

function favorite(entry, enabled) {
  try { workspace.value = setFavorite(session.value, entry, enabled) } catch (error) { tell(error) }
}

function sendFeedback() {
  feedbackError.value = ''
  try {
    workspace.value = submitFeedback(session.value, course.value, feedbackKinds[kindIndex.value], feedback.value)
    feedback.value = ''
    tell('已保存本机反馈')
  } catch (error) { feedbackError.value = error.message }
}
</script>
