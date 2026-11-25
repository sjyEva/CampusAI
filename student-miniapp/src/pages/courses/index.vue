<template>
  <StudyHeader title="CampusAI" :mode="session?.mode" :profile="workspace?.profile" @profile="drawerOpen = !!workspace" />
  <view class="study-page study-home">
    <view v-if="loadError" class="study-section">
      <view class="study-error">{{ loadError }}</view>
      <view class="study-actions">
        <button class="study-btn" @tap="load(true)">重新加载</button>
        <button v-if="getToken()" class="study-btn" @tap="backToDemo">返回演示</button>
      </view>
    </view>
    <view v-if="workspace" class="study-section fold">
      <view class="fold-heading">
        <view class="fold-title"><text class="todo-heading">TO DO LIST</text><text class="study-small fold-count">已完成 {{ doneCount }} / {{ workspace.todos.length }}</text></view>
        <button class="study-text-btn fold-toggle" :aria-expanded="!workspace.collapsed.todo"
          :aria-label="workspace.collapsed.todo ? '展开待办清单' : '收起待办清单'" @tap="toggleSection('todo')">
          {{ workspace.collapsed.todo ? '展开 ﹀' : '收起 ︿' }}
        </button>
      </view>
      <template v-if="!workspace.collapsed.todo">
        <view class="progress"><view class="progress-fill" :style="{ width: progress + '%' }" /></view>
        <view v-if="!workspace.todos.length" class="study-note">还没有待办事项</view>
        <view v-for="(item, index) in workspace.todos" :key="item.id" class="todo-row">
          <checkbox-group class="todo-content" @change="toggleTodo(item, $event)">
            <label class="todo-label">
              <checkbox class="todo-check" :value="item.id" :checked="item.done" color="#665335" />
              <text class="todo-text" :class="{ completed: item.done }">{{ item.text }}</text>
            </label>
          </checkbox-group>
          <view class="todo-actions">
            <button class="study-text-btn todo-move" :disabled="index === 0" :aria-label="'上移 ' + item.text" @tap="update(moveTodo, item.id, -1)">↑</button>
            <button class="study-text-btn todo-move" :disabled="index === workspace.todos.length - 1" :aria-label="'下移 ' + item.text" @tap="update(moveTodo, item.id, 1)">↓</button>
            <button class="study-text-btn todo-edit" :aria-label="'编辑 ' + item.text" @tap="editTodo(item)">编辑</button>
          </view>
        </view>
        <view class="add-todo">
          <input v-model="draft" class="study-field todo-input" maxlength="64" placeholder="添加一项待办" aria-label="新待办" @confirm="addTodo" />
          <button class="study-btn todo-add-button" @tap="addTodo">添加</button>
        </view>
      </template>
    </view>
    <view v-if="workspace" class="study-section fold">
      <view class="fold-heading">
        <view class="fold-title"><text class="study-heading">课程列表</text><text class="study-small fold-count">{{ matchedCourses.length }} 门</text></view>
        <button class="study-text-btn fold-toggle" :aria-expanded="!workspace.collapsed.courses"
          :aria-label="workspace.collapsed.courses ? '展开课程列表' : '收起课程列表'" @tap="toggleSection('courses')">
          {{ workspace.collapsed.courses ? '展开 ﹀' : '收起 ︿' }}
        </button>
      </view>
      <template v-if="!workspace.collapsed.courses">
        <view class="catalog-tools"><input v-model="query" class="study-field search" confirm-type="search" aria-label="搜索课程" placeholder="搜索课程" /></view>
        <view v-if="loading" class="study-note">正在加载课程…</view>
        <view v-else-if="!matchedCourses.length && !loadError" class="study-note">{{ query.trim() ? '没有匹配的课程' : '暂无已选课程' }}</view>
        <navigator v-for="course in matchedCourses" :key="course.id" class="course-row"
          :url="'/pages/courses/detail?id=' + course.id" :aria-label="'进入 ' + course.name">
          <view class="study-mark" />
          <view class="course-copy">
            <view class="study-subtitle">{{ course.name }}</view>
            <view class="study-small description">{{ course.description }}</view>
            <view class="study-caption code">{{ course.courseCode }}{{ course.teacher ? ' · ' + course.teacher : '' }}</view>
          </view>
          <view class="study-arrow" />
        </navigator>
      </template>
    </view>
  </view>
  <StudentDrawer v-if="drawerOpen && workspace && session" :key="session.owner" :workspace="workspace" :session="session"
    @close="drawerOpen = false" @change="workspace = $event" />
  <view v-if="editing" class="todo-editor">
    <view class="edit-shade" @tap="editing = null" />
    <view class="edit-sheet study-stack">
      <view class="fold-heading">
        <text class="study-heading">编辑待办</text>
        <button class="study-text-btn" @tap="editing = null">关闭</button>
      </view>
      <input v-model="editText" class="study-field" maxlength="64" aria-label="待办内容" @confirm="commitEdit" />
      <view class="study-actions">
        <button class="study-btn is-primary" @tap="commitEdit">保存修改</button>
        <button class="study-text-btn remove" @tap="deleteTodo">移除待办</button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref, shallowRef } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import StudyHeader from '../../components/StudyHeader.vue'
import StudentDrawer from '../../components/StudentDrawer.vue'
import { studyCourses } from '../../api/study.js'
import { signOut, studentSession } from '../../api/session.js'
import { getWorkspace, moveTodo, removeTodo, saveTodo, setSection } from '../../api/workspace.js'
import { getToken } from '../../utils/auth.js'
import { tell } from '../../utils/study-ui.js'

const session = shallowRef(null)
const workspace = ref(null)
const courses = ref([])
const query = ref('')
const draft = ref('')
const loadError = ref('')
const loading = ref(false)
const drawerOpen = ref(false)
const editing = ref(null)
const editText = ref('')
let loadedOwner = ''
const matchedCourses = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return courses.value.filter(course => (course.name + ' ' + course.courseCode).toLowerCase().includes(keyword))
})
const doneCount = computed(() => workspace.value?.todos.filter(item => item.done).length || 0)
const progress = computed(() => Math.round(100 * doneCount.value / (workspace.value?.todos.length || 1)))

onShow(() => load())

async function load(force = false) {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const active = await studentSession()
    if (session.value && session.value.owner !== active.owner) {
      query.value = ''
      draft.value = ''
      drawerOpen.value = false
      editing.value = null
      courses.value = []
    }
    session.value = active
    workspace.value = getWorkspace(active)
    if (force || loadedOwner !== active.owner) {
      courses.value = await studyCourses(active)
      loadedOwner = active.owner
    }
  } catch (error) {
    loadError.value = error.message
  } finally {
    loading.value = false
  }
}

async function backToDemo() {
  await signOut()
  load(true)
}

function update(operation, ...args) {
  if (!session.value) return false
  try {
    workspace.value = operation(session.value, ...args)
    return true
  } catch (error) {
    tell(error)
    return false
  }
}

function toggleSection(section) {
  update(setSection, section, !workspace.value.collapsed[section])
}

function toggleTodo(item, event) {
  update(saveTodo, { id: item.id, done: event.detail.value.includes(item.id) })
}

function addTodo() {
  if (update(saveTodo, { text: draft.value })) draft.value = ''
}

function editTodo(item) {
  editing.value = item
  editText.value = item.text
}

function commitEdit() {
  if (editing.value && update(saveTodo, { id: editing.value.id, text: editText.value })) editing.value = null
}

function deleteTodo() {
  if (editing.value && update(removeTodo, editing.value.id)) editing.value = null
}
</script>
