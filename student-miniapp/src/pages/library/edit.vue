<template>
  <view class="study-page">
    <view v-if="loading" class="study-note">正在读取资料…</view>
    <view v-else-if="loadError" class="study-error">{{ loadError }}</view>
    <view v-else class="study-stack">
      <view class="study-heading">{{ title }}</view>
      <view v-if="form.kind === 'file' && !form.id" class="study-caption">支持 PDF、PPT、Word、Excel、TXT 和 Markdown，单文件最大 10 MB。</view>
      <view class="form-row">
        <text>{{ nameLabel }}</text>
        <input v-model="form.name" class="study-field" maxlength="100" :aria-label="nameLabel" placeholder="输入名称" />
      </view>
      <picker :range="destinations" range-key="name" :value="courseIndex" @change="courseIndex = Number($event.detail.value)">
        <view class="study-field study-picker"><text>归入课程</text><text>{{ destinations[courseIndex].name }} ›</text></view>
      </picker>
      <picker :range="categories" :value="categoryIndex" @change="categoryIndex = Number($event.detail.value)">
        <view class="study-field study-picker"><text>分类</text><text>{{ categories[categoryIndex] }} ›</text></view>
      </picker>
      <view v-if="showText" class="form-row">
        <text>{{ form.kind === 'note' ? '笔记正文' : '文本内容' }}</text>
        <textarea v-model="form.text" class="study-field study-textarea" maxlength="12000" aria-label="资料正文" placeholder="填写正文" />
      </view>
      <view v-if="form.kind === 'file'" class="study-actions">
        <button class="study-btn" @tap="pick">{{ form.id ? '替换文件' : '选择文件' }}</button>
        <text class="study-small filename">{{ picked?.name || form.filename }}</text>
      </view>
      <view v-if="error" class="study-error">{{ error }}</view>
      <button class="study-btn is-primary save" :disabled="saving" :loading="saving" @tap="save">{{ form.id ? '保存修改' : '保存到个人资料库' }}</button>
    </view>
  </view>
</template>

<script setup>
import { computed, reactive, ref, shallowRef } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { categories, getWorkspace, savePersonalEntry } from '../../api/workspace.js'
import { studyCourses } from '../../api/study.js'
import { studentSession } from '../../api/session.js'
import { chooseFile } from '../../utils/files.js'
import { tell } from '../../utils/study-ui.js'

const session = shallowRef(null)
const form = reactive({ id: '', kind: 'file', name: '', filename: '', text: '', editableText: false })
const picked = shallowRef(null)
const destinations = ref([{ id: '', name: '未归入课程' }])
const courseIndex = ref(0)
const categoryIndex = ref(0)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const loadError = ref('')
const title = computed(() => form.id ? (form.kind === 'note' ? '编辑笔记' : '编辑资料') : (form.kind === 'note' ? '写笔记' : '导入资料'))
const nameLabel = computed(() => form.kind === 'note' ? '笔记标题' : form.id ? '资料名称' : '资料名称（可选）')
const showText = computed(() => form.kind === 'note' || (form.editableText && !picked.value))

onLoad(async options => {
  try {
    session.value = await studentSession()
    const old = options?.id ? getWorkspace(session.value).library.find(item => item.id === options.id) : null
    if (options?.id && !old) throw new Error('个人资料不存在')
    if (old) Object.assign(form, old)
    else form.kind = options?.kind === 'note' ? 'note' : 'file'
    categoryIndex.value = Math.max(0, categories.indexOf(old?.category || (form.kind === 'note' ? '课堂笔记' : '课件')))
    const courses = await studyCourses(session.value)
    destinations.value = [destinations.value[0], ...courses.map(course => ({ id: String(course.id), name: course.name }))]
    const initial = old?.courseId ?? options?.courseId
    if (old?.courseId && !destinations.value.some(item => item.id === old.courseId)) {
      destinations.value.push({ id: old.courseId, name: old.courseName || '原课程' })
    }
    courseIndex.value = Math.max(0, destinations.value.findIndex(course => course.id === initial))
  } catch (failure) { loadError.value = failure.message }
  finally { loading.value = false }
})

async function pick() {
  try {
    picked.value = await chooseFile()
    error.value = ''
  } catch (failure) {
    if (failure.message !== '已取消选择') error.value = failure.message
  }
}

async function save() {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    const course = destinations.value[courseIndex.value]
    await savePersonalEntry(session.value, {
      ...form, courseId: course.id, courseName: course.id ? course.name : '', category: categories[categoryIndex.value]
    }, picked.value)
    tell(form.id ? '已保存修改' : '已保存到个人资料库')
    uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/courses/index' }) })
  } catch (failure) { error.value = failure.message }
  finally { saving.value = false }
}
</script>

<style scoped>
.form-row { display: flex; flex-direction: column; gap: 9rpx; }
.filename { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.save { align-self: flex-start; }
</style>
