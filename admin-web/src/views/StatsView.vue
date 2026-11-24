<script setup>
import { onMounted, ref } from 'vue'
import { getLearningStats } from '../api/content.js'
import PageHead from '../components/PageHead.vue'
import { toast } from '../utils/toast.js'

const stats = ref(null)
const period = ref('近 7 天')

onMounted(async () => {
  stats.value = (await getLearningStats()).data
})
</script>

<template>
  <div class="desk-page">
  <PageHead title="学习统计" en="Statistics" description="用样例记录核对课程访问、资料访问、热门问题和练习正确率。">
    <el-select v-model="period" class="tool-period" @change="toast('已切换统计范围：' + period)">
      <el-option label="近 7 天" value="近 7 天" />
      <el-option label="近 30 天" value="近 30 天" />
      <el-option label="本学期" value="本学期" />
    </el-select>
  </PageHead>
  <div v-if="stats" class="stat-row">
    <div>
      <span>课程访问</span>
      <strong>{{ stats.courseVisits }}</strong>
      <em>{{ stats.visitors }} 人来过</em>
    </div>
    <div>
      <span>资料访问</span>
      <strong>{{ stats.materialVisitCount }}</strong>
      <em>累计打开次数</em>
    </div>
    <div>
      <span>练习正确率</span>
      <strong>{{ stats.accuracy }}</strong>
      <em>完成 {{ stats.exercises }} 次</em>
    </div>
    <div>
      <span>有依据回答</span>
      <strong>{{ stats.groundedRate }}</strong>
      <em>提问 {{ stats.questions }} 次</em>
    </div>
  </div>
  <div v-if="stats" class="split">
    <div class="block">
      <h3>各章练习正确率</h3>
      <table>
        <thead><tr><th>章节</th><th>正确率</th><th>作答人次</th></tr></thead>
        <tbody>
          <tr v-for="row in stats.chapterAccuracy" :key="row.chapter">
            <td>{{ row.chapter }}</td>
            <td :class="row.low ? 'st bad' : ''">{{ row.rate }}</td>
            <td>{{ row.attempts }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="block">
      <h3>资料访问</h3>
      <table>
        <thead><tr><th>资料</th><th>访问</th><th>收藏</th></tr></thead>
        <tbody>
          <tr v-for="row in stats.materialRows" :key="row.name">
            <td>{{ row.name }}</td>
            <td>{{ row.visits }}</td>
            <td>{{ row.saves }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
  <div v-if="stats" class="block stack">
    <h3>学生学习记录</h3>
    <table>
      <thead><tr><th>学生</th><th>最近学习</th><th>练习正确率</th><th>提问次数</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="row in stats.studentRecords" :key="row.name">
          <td>{{ row.name }}</td>
          <td>{{ row.recent }}</td>
          <td>{{ row.rate }}</td>
          <td>{{ row.asks }}</td>
          <td><button class="link" type="button" @click="toast('打开该学生的练习与问答记录')">记录</button></td>
        </tr>
      </tbody>
    </table>
  </div>
  <p class="foot">验收时保留：输入场景 → 预期结果 → 实际结果。第 3 章正确率明显偏低，需与练习记录核对。以上数字是示例，不依赖真实埋点。</p>
  </div>
</template>
