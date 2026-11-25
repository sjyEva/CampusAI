<template>
  <button v-if="!expanded" class="study-btn is-primary study-launcher" @tap="openChat">
    <text class="dots">···</text>课程问答
  </button>
  <view v-else class="study-assistant">
    <view class="assistant-head">
      <text class="study-subtitle">课程问答</text>
      <text class="study-tag">{{ session.mode === 'live' ? 'RAG 真实接口' : '演示数据' }}</text>
      <button class="study-text-btn assistant-close" aria-label="收起课程问答" @tap="expanded = false">收起</button>
    </view>
    <view class="study-caption context">{{ context || course.name }}</view>
    <scroll-view class="conversation" scroll-y :scroll-into-view="anchor" scroll-with-animation>
      <view v-if="!chat.turns.length" class="study-note">可以就本课程的资料提问。</view>
      <view v-for="turn in chat.turns" :key="turn.id" class="turn">
        <view class="question study-text">{{ turn.question }}</view>
        <view class="answer study-text">
          <view class="study-caption">{{ statusLabel(turn.status) }}</view>
          <view>{{ turn.answer }}</view>
          <view v-for="source in turn.sources" :key="source.chunkId" class="source">
            <view class="source-name">{{ source.filename }}</view>
            <view class="study-caption">{{ sourceLocation(source) }}</view>
            <view class="study-actions">
              <button class="study-text-btn source-action" @tap="inspect(turn, source, false)">{{ selected?.key === turn.id + source.chunkId ? '收起片段' : '查看片段' }}</button>
              <button class="study-text-btn source-action" @tap="inspect(turn, source, true)">打开原资料</button>
            </view>
            <view v-if="selected?.key === turn.id + source.chunkId" class="excerpt study-text">{{ selected.text }}</view>
          </view>
        </view>
      </view>
      <view v-if="chat.pending" class="study-note">正在查找资料…</view>
      <view v-if="chat.error" class="study-error">{{ chat.error }}</view>
      <view id="conversation-bottom" class="anchor" />
    </scroll-view>
    <view v-if="!chat.turns.length && !chat.pending && examples.length" class="study-actions examples">
      <button v-for="question in examples" :key="question" class="study-btn example" @tap="send(question)">{{ question }}</button>
    </view>
    <view class="composer">
      <input v-model="chat.draft" class="study-field question-input" maxlength="500" placeholder="输入课程问题"
        aria-label="课程问题" confirm-type="send" :disabled="chat.pending" @confirm="send(chat.draft)" />
      <button class="study-btn is-primary send" :disabled="chat.pending || !chat.draft.trim()" @tap="send(chat.draft)">发送</button>
    </view>
  </view>
</template>

<script>
import { reactive } from 'vue'

const conversations = new Map()

function historyFor(session, course) {
  const key = [session.token || session.owner, course.id].join(':')
  if (!conversations.has(key)) conversations.set(key, reactive({ turns: [], draft: '', pending: false, error: '' }))
  return conversations.get(key)
}
</script>
<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { exampleQuestions, sourceLocation, studyAnswer, studySource } from '../api/study.js'
import { readEntry, tell } from '../utils/study-ui.js'

const props = defineProps({
  session: { type: Object, required: true }, course: { type: Object, required: true },
  context: String, startOpen: Boolean
})
const emit = defineEmits(['expanded'])
const expanded = ref(props.startOpen)
const chat = computed(() => historyFor(props.session, props.course))
const examples = computed(() => exampleQuestions(props.session, props.course))
const anchor = ref('')
const selected = ref(null)
let inspecting = false
watch(() => [chat.value.turns.length, chat.value.pending], () => { if (expanded.value) scrollDown() })
watch(expanded, value => emit('expanded', value), { immediate: true })

function statusLabel(status) {
  return ({
    demo: '演示回答', generated: '课程助手', retrieval_only: '仅返回相关原文',
    no_evidence: '未找到依据', sources_changed: '资料已变更'
  })[status] || '课程助手'
}

async function scrollDown() {
  anchor.value = ''
  await nextTick()
  anchor.value = 'conversation-bottom'
}

function openChat() {
  expanded.value = true
  scrollDown()
}

async function send(value) {
  const question = value.trim()
  const state = chat.value
  if (!question || state.pending) return
  state.pending = true
  state.error = ''
  try {
    const result = await studyAnswer(props.session, props.course, question)
    state.turns.push({ id: state.turns.length + 1, question, ...result })
    if (state.draft.trim() === question) state.draft = ''
  } catch (error) {
    state.error = error.message
  } finally {
    state.pending = false
  }
}

async function inspect(turn, source, openFile) {
  const key = turn.id + source.chunkId
  if (!openFile && selected.value?.key === key) {
    selected.value = null
    return
  }
  if (inspecting) return
  inspecting = true
  try {
    const fresh = await studySource(props.session, props.course, source.chunkId, source)
    if (openFile) {
      readEntry({
        origin: props.session.mode, id: fresh.materialId, courseId: fresh.courseId, version: fresh.version
      }, fresh.chunkId)
    } else selected.value = { key, text: fresh.text }
  } catch (error) {
    tell(error)
  } finally {
    inspecting = false
  }
}
</script>
