<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getLearningStats } from '../api/content.js'
import PageHead from '../components/PageHead.vue'
import { useCourseScope } from '../composables/courseScope.js'

const router = useRouter()
const { courses, visibleCourses, loading, error, scopeLabel } = useCourseScope()
const stats = ref(null)

const tones = ['#161513', '#7a7266', '#d4cbb8']

onMounted(async () => {
  stats.value = (await getLearningStats()).data
})

const shownCourses = computed(() => (visibleCourses.value.length ? visibleCourses.value : courses.value))
const pending = computed(() => [
  {
    eyebrow: '资料还不能用',
    text: 'CNN 卷积讲义提取不出文本，问答引用不到',
    action: '去处理',
    route: 'docs',
  },
  {
    eyebrow: '热门问题没有依据',
    text: '「梯度消失如何缓解」被问到，但资料里还没有出处',
    action: '去处理',
    route: 'qa',
  },
  {
    eyebrow: '这一章明显偏弱',
    text: picture.value
      ? `${picture.value.weak.chapter}正确率 ${picture.value.weak.rate}%，需要核对练习`
      : '第 3 章练习正确率偏低，需要核对',
    action: '去处理',
    route: 'stats',
  },
])

const picture = computed(() => {
  const data = stats.value
  if (!data) return null
  const frequency = data.visitFrequency.map((item, index) => ({
    ...item,
    tone: tones[index],
    peopleShare: percent(item.people, data.visitors),
    visitShare: percent(item.visits, data.courseVisits),
  }))
  const materialTotal = data.materialRows.reduce((sum, row) => sum + row.visits, 0)
  const materials = data.materialRows.map((row, index) => ({
    name: row.name.replace(/\.(pdf|docx|txt)$/i, ''),
    share: percent(row.visits, materialTotal),
    tone: tones[index],
  }))
  const mean = Number.parseInt(data.accuracy, 10)
  const chapters = data.chapterAccuracy.map((row) => {
    const rate = Number.parseInt(row.rate, 10)
    return { chapter: row.chapter, rate, delta: rate - mean, low: rate < mean }
  })
  const weak = chapters.reduce((lowest, row) => (row.delta < lowest.delta ? row : lowest))
  const groundedCount = Math.round((data.questions * Number.parseInt(data.groundedRate, 10)) / 100)
  const ungroundedCount = data.questions - groundedCount
  const hot = data.hotQuestions.map((item) => ({
    question: item.question,
    share: percent(item.count, data.questions),
    missing: item.question.includes('梯度消失'),
  }))
  return {
    visitRate: percent(data.visitors, data.enrolled),
    enrolled: data.enrolled,
    visitors: data.visitors,
    frequency,
    frequent: frequency[0],
    materials,
    leadMaterial: materials[0],
    mean,
    chapters,
    weak,
    groundedRate: data.groundedRate,
    groundedCount,
    ungroundedCount,
    ungroundedRate: percent(ungroundedCount, data.questions),
    hot,
    hotShare: percent(data.hotQuestions.reduce((sum, item) => sum + item.count, 0), data.questions),
    missingHot: hot.find((item) => item.missing),
  }
})

const donut = computed(() => {
  if (!picture.value) return ''
  let cursor = 0
  const stops = picture.value.materials.map((item) => {
    const start = cursor
    cursor += item.share
    return `${item.tone} ${start}% ${cursor}%`
  })
  return `conic-gradient(${stops.join(', ')})`
})

function percent(part, total) {
  if (!total) return 0
  return Math.round((part / total) * 100)
}

function signed(value) {
  return value > 0 ? `+${value}` : `${value}`
}
</script>

<template>
  <div class="desk-page">
    <PageHead
      title="工作台"
      en="Overview"
      description="判断负责课程里，学生有没有来、主要在看什么、哪里没学会，以及哪些提问还没有依据。"
    />

    <section class="pending-board">
      <h3>待处理 · {{ pending.length }}</h3>
      <div class="action-row">
        <button
          v-for="item in pending"
          :key="item.route"
          class="block action"
          type="button"
          @click="router.push({ name: item.route })"
        >
          <span>{{ item.eyebrow }}</span>
          <strong>{{ item.text }}</strong>
          <em>{{ item.action }}</em>
        </button>
      </div>
    </section>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-else class="scope-pills">
      <span>{{ loading ? '正在读取课程' : scopeLabel }}</span>
      <em v-for="course in shownCourses" :key="course.id">{{ course.courseCode }} {{ course.name }}</em>
    </div>

    <template v-if="picture">
      <div class="desk-grid desk-visit">
        <section class="block">
          <h3>课程到访</h3>
          <div class="pad visit-body">
            <div class="visit-rate">
              <strong>{{ picture.visitRate }}%</strong>
              <span>到访比例</span>
              <p>{{ picture.enrolled }} 名学生里，{{ picture.visitors }} 人来过</p>
            </div>
            <div class="visit-freq">
              <p class="callout">
                {{ picture.frequent.people }} 人{{ picture.frequent.label }}，贡献了 {{ picture.frequent.visitShare }}% 的访问
              </p>
              <div class="stack-line">
                <span>人数</span>
                <div class="stack-bar">
                  <i
                    v-for="item in picture.frequency"
                    :key="`people-${item.label}`"
                    :style="{ width: `${item.peopleShare}%`, background: item.tone }"
                  />
                </div>
              </div>
              <div class="stack-line">
                <span>访问</span>
                <div class="stack-bar">
                  <i
                    v-for="item in picture.frequency"
                    :key="`visits-${item.label}`"
                    :style="{ width: `${item.visitShare}%`, background: item.tone }"
                  />
                </div>
              </div>
              <ul class="mix-legend">
                <li v-for="item in picture.frequency" :key="item.label">
                  <i class="swatch" :style="{ background: item.tone }" />
                  <span>{{ item.label }}</span>
                  <b>{{ item.people }} 人 · {{ item.visitShare }}% 访问</b>
                </li>
              </ul>
            </div>
          </div>
        </section>

        <section class="block">
          <h3>主要在看什么</h3>
          <div class="pad mix-body">
            <div class="donut" :style="{ background: donut }">
              <span>{{ picture.leadMaterial.share }}%</span>
            </div>
            <div>
              <p class="callout">{{ picture.leadMaterial.name }}占 {{ picture.leadMaterial.share }}%</p>
              <ul class="mix-legend">
                <li v-for="item in picture.materials" :key="item.name">
                  <i class="swatch" :style="{ background: item.tone }" />
                  <span>{{ item.name }}</span>
                  <b>{{ item.share }}%</b>
                </li>
              </ul>
            </div>
          </div>
        </section>
      </div>

      <div class="desk-grid">
        <section class="block">
          <h3>哪里没学会</h3>
          <div class="pad">
            <p class="callout">
              {{ picture.weak.chapter }}只有 {{ picture.weak.rate }}%，比课程正确率低 {{ Math.abs(picture.weak.delta) }} 个百分点
            </p>
            <div class="gap-chart">
              <div v-for="row in picture.chapters" :key="row.chapter" class="gap-row">
                <span class="bar-name">{{ row.chapter }}</span>
                <span class="gap-track">
                  <span class="gap-fill" :class="{ low: row.chapter === picture.weak.chapter }" :style="{ width: `${row.rate}%` }" />
                  <span class="gap-line" :style="{ left: `${picture.mean}%` }" />
                </span>
                <span class="gap-value" :class="{ low: row.chapter === picture.weak.chapter }">{{ signed(row.delta) }}</span>
              </div>
            </div>
            <p class="quiet">竖线是课程正确率 {{ picture.mean }}%，右侧是与它的差距</p>
          </div>
        </section>

        <section class="block">
          <h3>提问有没有依据</h3>
          <div class="pad">
            <p class="callout">
              {{ picture.ungroundedCount }} 问对不上资料。热门里的「{{ picture.missingHot.question }}」占 {{ picture.missingHot.share }}%，还没有依据
            </p>
            <div class="split-meter" aria-hidden="true">
              <i class="yes" :style="{ width: picture.groundedRate }" />
              <i class="no" />
            </div>
            <div class="split-key">
              <span>有依据 {{ picture.groundedRate }}</span>
              <span class="miss">没有依据 {{ picture.ungroundedRate }}%</span>
            </div>
            <p class="quiet">下面四个问题占了全部提问的 {{ picture.hotShare }}%</p>
            <ul class="hot-share">
              <li v-for="item in picture.hot" :key="item.question" :class="{ missing: item.missing }">
                <span>{{ item.question }}</span>
                <b>{{ item.share }}%</b>
                <em v-if="item.missing">没有依据</em>
              </li>
            </ul>
          </div>
        </section>
      </div>

    </template>
  </div>
</template>
