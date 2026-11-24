<script setup>
import { computed, onMounted, ref } from 'vue'
import { listMaterials } from '../api/content.js'
import PageHead from '../components/PageHead.vue'
import { toast } from '../utils/toast.js'

const rows = ref([])
const keyword = ref('')
const status = ref('全部状态')
const uploadOpen = ref(false)
const failOpen = ref(false)
const draftName = ref('')

onMounted(async () => {
  rows.value = (await listMaterials()).data
})

function matchesStatus(row) {
  if (status.value === '全部状态') return true
  if (status.value === '已发布') return row.publish === '已发布'
  if (status.value === '解析成功') return row.parse === '成功'
  if (status.value === '解析失败') return row.parse === '失败'
  if (status.value === '解析中') return row.parse === '解析中'
  return true
}

const filtered = computed(() => rows.value.filter((row) => row.name.includes(keyword.value.trim()) && matchesStatus(row)))

function startParse() {
  uploadOpen.value = false
  toast('已加入解析队列，成功前不能发布')
}
</script>

<template>
  <div class="desk-page">
  <PageHead title="资料管理" en="Materials" description="支持 PDF / DOCX / TXT。解析成功后才能发布；失败需记录原因。">
    <el-input v-model="keyword" class="tool-search" placeholder="按名称查找" clearable />
    <el-select v-model="status" class="tool-status">
      <el-option label="全部状态" value="全部状态" />
      <el-option label="解析中" value="解析中" />
      <el-option label="解析成功" value="解析成功" />
      <el-option label="解析失败" value="解析失败" />
      <el-option label="已发布" value="已发布" />
    </el-select>
    <el-button type="primary" @click="uploadOpen = true">上传资料</el-button>
  </PageHead>
  <div class="sheet">
  <table class="data">
    <thead>
      <tr><th>资料名称</th><th>章节</th><th>类型</th><th>解析</th><th>定位信息</th><th>发布</th><th>操作</th></tr>
    </thead>
    <tbody>
      <tr v-if="filtered.length === 0"><td colspan="7">没有符合条件的资料。</td></tr>
      <tr v-for="row in filtered" :key="row.name">
        <td>{{ row.name }}</td>
        <td>{{ row.chapter }}</td>
        <td>{{ row.type }}</td>
        <td :class="row.parseKind === 'ok' ? 'st-ok' : row.parseKind === 'bad' ? 'st bad' : 'st'">
          {{ row.parse === '成功' ? '成功' : row.parse === '失败' ? '失败' : row.parse }}
        </td>
        <td>{{ row.locate }}</td>
        <td :class="row.publishKind === 'ok' ? 'st-ok' : 'st'">{{ row.publish }}</td>
        <td>
          <template v-if="row.parse === '成功' && row.publish === '已发布'">
            <button class="link" type="button" @click="toast('预览来源：第 4 章 / 第 6 页')">原文</button>
            <button class="link" type="button" @click="toast('开始下载')">下载</button>
          </template>
          <template v-else-if="row.parse === '成功'">
            <button class="link" type="button" @click="toast('已发布，学生可检索')">发布</button>
            <button class="link" type="button" @click="toast('已删除未发布资料')">删除</button>
          </template>
          <template v-else-if="row.parse === '解析中'">
            <button class="link" type="button" @click="toast('解析完成后才可发布')">进度</button>
          </template>
          <template v-else>
            <button class="link" type="button" @click="failOpen = true">原因</button>
            <button class="link" type="button" @click="toast('请改用文本型文件再传')">重传</button>
          </template>
        </td>
      </tr>
    </tbody>
  </table>
  </div>
  <p class="foot">PDF 按页码定位；DOCX / TXT 按章节或段落定位。扫描件需先转文本再导入。本表是示例数据，查找和筛选只作用在这份样例上。</p>

  <el-dialog v-model="uploadOpen" title="上传资料" width="480px" class="glass-dialog" align-center>
    <p class="dialog-note">仅支持文本型 PDF、DOCX、TXT。先选择课程与章节，以便保留来源位置。</p>
    <div class="field">
      <label>课程 / 章节</label>
      <el-select model-value="深度学习 / 第 5 章 卷积网络">
        <el-option label="深度学习 / 第 5 章 卷积网络" value="深度学习 / 第 5 章 卷积网络" />
      </el-select>
    </div>
    <div class="field"><label>资料名称</label><el-input v-model="draftName" placeholder="例如：CNN 卷积讲义.pdf" /></div>
    <div class="field"><label>文件</label><el-input model-value="尚未选择文件（原型）" disabled /></div>
    <template #footer>
      <el-button class="btn-ghost" @click="uploadOpen = false">取消</el-button>
      <el-button type="primary" @click="startParse">开始解析</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="failOpen" title="解析失败" width="480px" class="glass-dialog" align-center>
    <p class="dialog-note">CNN 卷积讲义.pdf 未被识别为文本型 PDF，可能是扫描件。</p>
    <p class="dialog-note">处理：转为可复制文本的 PDF，或改传 TXT / DOCX。失败资料不能发布。</p>
    <template #footer>
      <el-button class="btn-ghost" @click="failOpen = false">关闭</el-button>
      <el-button type="primary" @click="failOpen = false; toast('请重新上传文本型文件')">重新上传</el-button>
    </template>
  </el-dialog>
  </div>
</template>
