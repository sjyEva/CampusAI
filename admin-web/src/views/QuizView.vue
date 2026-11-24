<script setup>
import { onMounted, ref } from 'vue'
import { listQuestions } from '../api/content.js'
import PageHead from '../components/PageHead.vue'
import { toast } from '../utils/toast.js'

const rows = ref([])
const editing = ref(false)
const form = ref(emptyForm())

function emptyForm() {
  return {
    stem: '',
    options: ['', '', ''],
    answer: '',
    analysis: '',
  }
}

onMounted(async () => {
  rows.value = (await listQuestions()).data
})

function openQuestion(row) {
  form.value = row
    ? { stem: row.stem, options: [...row.options], answer: row.answer, analysis: row.analysis }
    : emptyForm()
  editing.value = true
}

function save() {
  editing.value = false
  toast('题目已保存')
}
</script>

<template>
  <div class="desk-page">
  <PageHead title="题库维护" en="Questions" description="维护题干、选项、答案、解析和知识点，供学生练习与自动判分。">
    <el-button type="primary" @click="openQuestion()">新增题目</el-button>
  </PageHead>
  <div class="sheet">
  <table class="data">
    <thead>
      <tr><th>题目</th><th>题型</th><th>知识点</th><th>章节</th><th>状态</th><th>操作</th></tr>
    </thead>
    <tbody>
      <tr v-for="row in rows" :key="row.stem">
        <td>{{ row.stem }}</td>
        <td>{{ row.type }}</td>
        <td>{{ row.topic }}</td>
        <td>{{ row.chapter }}</td>
        <td :class="row.status === '已发布' ? 'st-ok' : 'st'">{{ row.status }}</td>
        <td><button class="link" type="button" @click="openQuestion(row)">编辑</button></td>
      </tr>
    </tbody>
  </table>
  </div>
  <p class="foot">题型覆盖单选、多选和判断。本表是示例，保存不会写入后端。</p>

  <el-dialog v-model="editing" title="编辑题目" width="480px" class="glass-dialog" align-center>
    <div class="field"><label>题干</label><el-input v-model="form.stem" type="textarea" :rows="3" /></div>
    <div class="field">
      <label>选项</label>
      <el-input v-for="(_, index) in form.options" :key="index" v-model="form.options[index]" class="option-input" />
    </div>
    <div class="field"><label>正确答案</label><el-input v-model="form.answer" /></div>
    <div class="field"><label>解析 / 知识点</label><el-input v-model="form.analysis" /></div>
    <template #footer>
      <el-button class="btn-ghost" @click="editing = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
  </div>
</template>
