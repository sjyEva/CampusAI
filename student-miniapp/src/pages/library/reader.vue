<template>
  <view class="study-page study-reader" :class="{ 'with-assistant': course, 'chat-visible': chatVisible }">
    <view v-if="loading" class="study-note">正在打开资料…</view>
    <view v-else-if="error" class="study-error">{{ error }}</view>
    <template v-else-if="entry">
      <view class="study-section study-stack">
        <view class="study-heading">{{ entry.name }}</view>
        <view class="study-small">{{ entry.courseName || '未归入课程' }} · {{ entry.location || entry.category }}</view>
        <view class="study-actions">
          <button class="study-btn is-primary" :disabled="fileBusy" @tap="withOriginal(true)">下载原件</button>
          <button v-if="!isTextFile(entry.filename)" class="study-btn" :disabled="fileBusy" @tap="withOriginal(false)">打开原文件</button>
          <button v-if="entry.origin === 'personal'" class="study-btn" @tap="editEntry(entry)">编辑</button>
          <button class="study-btn" @tap="toggleFavorite">{{ favorited ? '已收藏' : '收藏' }}</button>
        </view>
        <view v-if="fileError" class="study-error">{{ fileError }}</view>
      </view>
      <view v-if="citation" class="study-section citation">
        <view class="study-caption">{{ sourceLocation(citation) }} · 引用片段</view>
        <view class="study-text">{{ citation.text }}</view>
      </view>
      <view v-if="slides.length" class="study-section study-stack">
        <view class="flip">
          <button class="study-btn" :disabled="page === 0" @tap="page -= 1">上一页</button>
          <text class="study-small">{{ slides[page].label }} / {{ slides.length }}</text>
          <button class="study-btn" :disabled="page === slides.length - 1" @tap="page += 1">下一页</button>
        </view>
        <image class="document-page" :src="slides[page].image" mode="widthFix" />
      </view>
      <view v-else-if="entry.origin === 'live'" class="study-section study-stack">
        <view class="study-subtitle">内容预览</view>
        <view v-if="previewError" class="study-error">{{ previewError }}</view>
        <view v-for="source in previewRows" :key="source.chunkId" class="preview-text">
          <view class="study-caption">{{ sourceLocation(source) }}</view>
          <view class="study-text">{{ source.text }}</view>
        </view>
        <button v-if="nextOffset !== null" class="study-btn" :disabled="previewPending" @tap="loadMore">{{ previewPending ? '正在读取…' : previewError ? '重试预览' : '继续阅读' }}</button>
        <view v-else-if="!previewRows.length" class="study-note">暂无可展示的内容，可打开原文件。</view>
      </view>
      <view v-else-if="isTextFile(entry.filename)" class="study-section">
        <view class="study-text">{{ entry.text || '文件内容为空' }}</view>
        <view v-if="entry.kind === 'file' && !entry.editableText && entry.text" class="study-note">此处展示文档开头，全文可下载查看。</view>
      </view>
      <view v-else class="study-section study-note">可使用顶部的「打开原文件」查看文档版式。</view>
    </template>
  </view>
  <CourseAssistant v-if="session && course && entry" :session="session" :course="course" :context="'正在阅读：' + entry.name"
    @expanded="chatVisible = $event" />
</template>

<script setup>
import { computed, ref, shallowRef } from 'vue'
import { onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import CourseAssistant from '../../components/CourseAssistant.vue'
import { studentSession } from '../../api/session.js'
import { demoPreview, originalFile, sourceLocation, studyCourse, studyMaterial, studyPreview, studySource } from '../../api/study.js'
import { entryKey, getWorkspace, setFavorite } from '../../api/workspace.js'
import { exportOriginal, forgetFile, isTextFile, openOriginal } from '../../utils/files.js'
import { editEntry, tell } from '../../utils/study-ui.js'

const session = shallowRef(null)
const course = shallowRef(null)
const entry = ref(null)
const workspace = ref(null)
const citation = ref(null)
const page = ref(0)
const loading = ref(true)
const error = ref('')
const fileError = ref('')
const fileBusy = ref(false)
const chatVisible = ref(false)
const previewPending = ref(false)
const previewError = ref('')
const previewRows = ref([])
const nextOffset = ref(0)
let alive = true
const ownedFiles = new Set()
const slides = computed(() => entry.value?.origin === 'demo' ? demoPreview(entry.value) : [])
const favorited = computed(() => Boolean(entry.value && workspace.value?.favorites.some(item => entryKey(item) === entryKey(entry.value))))

onLoad(async options => {
  try {
    session.value = await studentSession()
    workspace.value = getWorkspace(session.value)
    const id = options?.id || ''
    if (options?.origin === 'personal') {
      entry.value = workspace.value.library.find(item => item.id === id)
      if (!entry.value) throw new Error('个人资料不存在')
      if (entry.value.courseId) course.value = await studyCourse(session.value, entry.value.courseId).catch(() => null)
      return
    }
    const version = Number(options?.version)
    if (!id || !Number.isSafeInteger(version) || version < 1 || options?.origin !== session.value.mode) {
      throw new Error('请从当前课程打开资料')
    }
    course.value = await studyCourse(session.value, options.courseId)
    const material = await studyMaterial(session.value, course.value, id, version)
    if (options.chunk) {
      citation.value = await studySource(session.value, course.value, options.chunk, { materialId: id, version })
    }
    entry.value = material
    if (citation.value) page.value = Math.max(0, Math.min(slides.value.length - 1, (citation.value.slideNumber || citation.value.pageNumber || 1) - 1))
    if (material.origin === 'live') loadMore()
  } catch (failure) { error.value = failure.message }
  finally { loading.value = false }
})

onShow(async () => {
  if (!session.value) return
  workspace.value = getWorkspace(session.value)
  if (entry.value?.origin !== 'personal') return
  const current = workspace.value.library.find(item => item.id === entry.value.id)
  if (!current) return
  if (entry.value.courseId !== current.courseId) {
    course.value = current.courseId ? await studyCourse(session.value, current.courseId).catch(() => null) : null
  }
  entry.value = current
})
onUnload(() => {
  alive = false
  for (const path of ownedFiles) void forgetFile(path)
})

async function loadMore() {
  if (previewPending.value || nextOffset.value === null) return
  previewPending.value = true
  previewError.value = ''
  try {
    const result = await studyPreview(entry.value, nextOffset.value)
    previewRows.value.push(...result.sources.filter(source => source.chunkId !== citation.value?.chunkId))
    nextOffset.value = result.nextOffset
  } catch (failure) { previewError.value = failure.message }
  finally { previewPending.value = false }
}

function toggleFavorite() {
  try { workspace.value = setFavorite(session.value, entry.value, !favorited.value) }
  catch (failure) { tell(failure) }
}

async function withOriginal(download) {
  if (fileBusy.value) return
  const item = entry.value
  fileBusy.value = true
  fileError.value = ''
  try {
    const file = await originalFile(item, session.value)
    if (!alive) {
      if (file.owned) await forgetFile(file.path)
      return
    }
    if (file.owned) ownedFiles.add(file.path)
    await (download ? exportOriginal(file.path, item.filename) : openOriginal(file.path, item.filename))
  } catch (failure) {
    if (failure.message !== '已取消选择') fileError.value = failure.message
  } finally {
    fileBusy.value = false
  }
}
</script>
