<script setup>
import { computed, onMounted, ref } from 'vue'
import { listQaRecords } from '../api/content.js'
import PageHead from '../components/PageHead.vue'
import { toast } from '../utils/toast.js'

const rows = ref([])
const filter = ref('全部')
const current = ref(null)

onMounted(async () => {
  rows.value = (await listQaRecords()).data
})

const visible = computed(() => {
  if (filter.value === '全部') return rows.value
  if (filter.value === '反馈问题') return rows.value.filter((row) => row.filter === '未找到依据' || row.feedback.startsWith('反馈问题'))
  return rows.value.filter((row) => row.filter === filter.value || row.feedback.startsWith(filter.value))
})
</script>

<template>
  <div class="desk-page">
  <PageHead title="问答与反馈" en="Feedback" description="核对学生提问是否附带来源，以及是否提示未找到依据。">
    <el-select v-model="filter" class="tool-qa">
      <el-option label="全部" value="全部" />
      <el-option label="有帮助" value="有帮助" />
      <el-option label="反馈问题" value="反馈问题" />
      <el-option label="未找到依据" value="未找到依据" />
    </el-select>
  </PageHead>
  <div class="sheet">
  <table class="data">
    <thead>
      <tr><th>问题</th><th>回答状态</th><th>来源</th><th>反馈</th><th>操作</th></tr>
    </thead>
    <tbody>
      <tr v-if="visible.length === 0"><td colspan="5">没有符合条件的记录。</td></tr>
      <tr v-for="row in visible" :key="row.question">
        <td>{{ row.question }}</td>
        <td :class="row.answerKind === 'ok' ? 'st-ok' : row.answerKind === 'muted' ? 'st' : ''">{{ row.answerState }}</td>
        <td>{{ row.source }}</td>
        <td :class="row.feedbackKind === 'ok' ? 'st-ok' : row.feedbackKind === 'bad' ? 'st bad' : 'st'">{{ row.feedback }}</td>
        <td>
          <button v-if="row.answer" class="link" type="button" @click="current = row">详情</button>
          <button v-else class="link" type="button" @click="toast('建议补充第 4 章资料后再发布')">补充资料</button>
        </td>
      </tr>
    </tbody>
  </table>
  </div>
  <p class="foot">能区分附来源、未找到依据、仅返回原文。本表是示例，不编造缺失资料的引用。</p>

  <el-dialog :model-value="Boolean(current)" title="问答详情" width="480px" class="glass-dialog" align-center @close="current = null">
    <template v-if="current">
      <p class="dialog-note"><b>问题：</b>{{ current.question }}</p>
      <p class="dialog-note">回答：{{ current.answer }}</p>
      <p class="dialog-note">{{ current.detail }}</p>
    </template>
    <template #footer>
      <el-button class="btn-ghost" @click="current = null">关闭</el-button>
    </template>
  </el-dialog>
  </div>
</template>
