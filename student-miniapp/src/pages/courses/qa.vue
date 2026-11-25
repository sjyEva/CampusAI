<template>
  <view class="qa-page">
    <view v-if="loading" class="page muted">正在加载课程…</view>
    <view v-else-if="error" class="page">
      <view class="error">{{ error }}</view>
      <button v-if="courseId" class="retry" size="mini" @tap="loadCourse">重新加载</button>
    </view>

    <template v-else-if="course">
      <view class="course-header">
        <view class="course-heading">
          <view class="course-code">{{ course.courseCode }}</view>
          <view class="course-name">{{ course.name }}</view>
        </view>
        <text class="preview-label">原型预览</text>
      </view>

      <scroll-view
        class="conversation"
        scroll-y
        scroll-with-animation
        :scroll-into-view="scrollTarget"
      >
        <view class="conversation-inner">
          <view class="assistant-label">课程助手</view>
          <view class="assistant-card welcome-card">
            <text>{{ example ? '点一下试问，体验回答和原文引用。' : '这门课程暂时没有示例问答。' }}</text>
            <button
              v-if="example"
              class="example-question"
              size="mini"
              @tap="sendQuestion(example.question)"
            >
              {{ example.question }} <text aria-hidden="true">›</text>
            </button>
          </view>

          <view v-for="turn in turns" :key="turn.id" class="turn">
            <view class="question-bubble">{{ turn.question }}</view>
            <view class="assistant-label">课程助手</view>
            <view class="assistant-card">
              <view>{{ turn.answer }}</view>
              <view v-if="turn.source" class="source-card">
                <view class="source-heading">
                  <view class="source-meta">
                    <view class="source-title">{{ turn.source.title }}</view>
                    <view class="muted source-location">{{ turn.source.location }}</view>
                  </view>
                  <button class="source-action" size="mini" @tap="toggleSource(turn)">
                    {{ turn.expanded ? '收起原文' : '查看原文' }}
                  </button>
                </view>
                <view v-if="turn.expanded" class="source-text">{{ turn.source.text }}</view>
              </view>
            </view>
            <view :id="'qa-end-' + turn.id" class="scroll-anchor"></view>
          </view>
        </view>
      </scroll-view>

      <view v-if="example" class="composer">
        <input
          v-model="draft"
          class="question-input"
          aria-label="课程问题"
          :placeholder="turns.length ? '继续提问' : '输入课程问题'"
          maxlength="500"
          confirm-type="send"
          @confirm="sendQuestion(draft)"
        />
        <button class="primary send-button" :disabled="!draft.trim()" @tap="sendQuestion(draft)">
          发送
        </button>
      </view>
    </template>
  </view>
</template>

<script>
import { getCourse } from '../../api/courses.js'
import { getToken } from '../../utils/auth.js'

const examples = {
  CS101: {
    question: '计算机系统由什么组成？',
    answer: '计算机系统由硬件和软件组成。硬件包括处理器、内存和输入输出设备，软件包括系统软件与应用软件。',
    source: {
      title: '计算机导论样例资料',
      location: '第 1 段 · 计算机系统的组成',
      text: '计算机系统由硬件和软件共同组成。硬件包括中央处理器、内存、输入设备、输出设备和外部存储设备。软件分为系统软件与应用软件，操作系统是典型的系统软件，文字处理程序属于应用软件。'
    }
  },
  SE101: {
    question: '功能需求和非功能需求有什么区别？',
    answer: '功能需求说明系统要做什么，非功能需求说明系统应达到怎样的质量，例如性能、安全性和可用性。',
    source: {
      title: '软件工程样例资料',
      location: '第 1 段 · 需求与验收标准',
      text: '软件需求描述用户在特定场景中需要完成的任务以及系统必须满足的约束。功能需求说明系统要提供什么行为，非功能需求关注性能、安全、可用性等质量属性。'
    }
  }
}

function normalizeQuestion(question) {
  return question.trim().replace(/[？?]+$/, '').trim()
}

export default {
  data() {
    return { courseId: '', course: null, loading: false, error: '', draft: '', turns: [], scrollTarget: '' }
  },
  computed: {
    example() {
      const code = this.course?.courseCode
      return Object.prototype.hasOwnProperty.call(examples, code) ? examples[code] : null
    }
  },
  onLoad(options) {
    if (!getToken()) {
      uni.reLaunch({ url: '/pages/login/index' })
      return
    }
    if (!/^[1-9]\d*$/.test(options?.id || '')) {
      this.error = '课程编号无效'
      return
    }
    this.courseId = options.id
    this.loadCourse()
  },
  methods: {
    async loadCourse() {
      if (this.loading) return
      this.loading = true
      this.error = ''
      try {
        const result = await getCourse(this.courseId)
        if (!result.data?.id) throw new Error('课程详情响应格式有误')
        this.course = result.data
      } catch (error) {
        if (error.code === 401 && !getToken()) {
          uni.reLaunch({ url: '/pages/login/index' })
          return
        }
        this.error = error.message
      } finally {
        this.loading = false
      }
    },
    sendQuestion(value) {
      const question = value.trim()
      if (!question || !this.example) return
      const matches = normalizeQuestion(question) === normalizeQuestion(this.example.question)
      const id = this.turns.length + 1
      this.turns.push({
        id,
        question,
        answer: matches ? this.example.answer : '预览暂时只提供页面顶部示例问题的回答。',
        source: matches ? this.example.source : null,
        expanded: false
      })
      if (this.draft.trim() === question) this.draft = ''
      this.scrollToTurn(id)
    },
    toggleSource(turn) {
      turn.expanded = !turn.expanded
      if (turn.expanded) this.scrollToTurn(turn.id)
    },
    scrollToTurn(id) {
      this.scrollTarget = ''
      this.$nextTick(() => {
        this.scrollTarget = 'qa-end-' + id
      })
    }
  }
}
</script>

<style scoped>
.qa-page {
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 680px;
  height: calc(100vh - var(--window-top, 0px));
  margin: 0 auto;
  line-height: 1.55;
}

.course-header {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
  padding: 24rpx 32rpx;
  border-bottom: 1px solid var(--campus-line);
  background: var(--campus-surface);
}

.course-heading { min-width: 0; }
.course-code { color: var(--campus-accent); font-size: 24rpx; font-weight: 600; }
.course-name { font-size: 36rpx; font-weight: 650; overflow-wrap: anywhere; }

.preview-label {
  flex: none;
  padding: 4rpx 14rpx;
  border-radius: 99px;
  background: var(--campus-warm);
  color: var(--campus-accent);
  font-size: 22rpx;
}

.conversation { flex: 1; height: 0; min-height: 0; }
.conversation-inner { padding: 30rpx 32rpx 24rpx; }
.turn { margin-top: 30rpx; }

.assistant-label {
  margin: 0 0 10rpx 2rpx;
  color: var(--campus-muted);
  font-size: 24rpx;
  font-weight: 600;
}

.assistant-card,
.question-bubble {
  box-sizing: border-box;
  width: fit-content;
  max-width: 94%;
  padding: 22rpx 24rpx;
  border: 1px solid var(--campus-line);
  border-radius: 20rpx;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.assistant-card { background: var(--campus-surface); border-top-left-radius: 6rpx; }
.welcome-card { color: var(--campus-muted); }

.question-bubble {
  max-width: 84%;
  margin: 0 0 20rpx auto;
  border-top-right-radius: 6rpx;
  background: var(--campus-warm);
}

.example-question,
.source-action {
  margin: 0;
  padding: 10rpx 14rpx;
  border: 1px solid var(--campus-accent);
  border-radius: 10rpx;
  background: transparent;
  color: var(--campus-accent);
  line-height: 1.45;
  font-size: 24rpx;
}

.example-question { display: block; margin-top: 16rpx; text-align: left; white-space: normal; }
.example-question text { margin-left: 8rpx; }
.example-question::after, .source-action::after, .send-button::after { border: 0; }

.source-card {
  margin-top: 20rpx;
  padding: 18rpx 18rpx 18rpx 20rpx;
  border-left: 4rpx solid var(--campus-gold);
  border-radius: 12rpx;
  background: var(--campus-warm);
}

.source-heading { display: flex; align-items: center; gap: 12rpx; }
.source-meta { min-width: 0; flex: 1; }
.source-title { font-size: 27rpx; font-weight: 650; }
.source-location { margin-top: 4rpx; font-size: 23rpx; }
.source-action { flex: none; min-height: 64rpx; white-space: nowrap; }
.source-text { margin-top: 16rpx; padding-top: 16rpx; border-top: 1px solid var(--campus-line); font-size: 27rpx; }
.scroll-anchor { height: 1px; }

.composer {
  display: flex;
  flex: none;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 32rpx;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--campus-line);
  background: var(--campus-surface);
}

.question-input {
  flex: 1;
  min-width: 0;
  height: 84rpx;
  padding: 0 20rpx;
  border: 1px solid var(--campus-line-strong);
  border-radius: 12rpx;
}

.send-button { flex: none; min-width: 128rpx; height: 84rpx; margin: 0; padding: 0 24rpx; line-height: 84rpx; font-size: 28rpx; }
.retry { margin: 12rpx 0 0; }
</style>
