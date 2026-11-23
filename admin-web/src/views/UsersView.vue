<script setup>
import { onMounted, ref } from 'vue'
import { listUsers } from '../api/content.js'
import PageHead from '../components/PageHead.vue'
import { profile } from '../session.js'
import { toast } from '../utils/toast.js'

const rows = ref([])
const creating = ref(false)
const form = ref({ name: '', email: '', role: '学生', course: '深度学习' })

onMounted(async () => {
  rows.value = (await listUsers()).data
})

const roleText = { admin: '系统管理员', teacher: '教师', student: '学生' }

function createUser() {
  creating.value = false
  form.value = { name: '', email: '', role: '学生', course: '深度学习' }
  toast('已创建，权限按角色和课程范围生效（示例，尚未写入）')
}
</script>

<template>
  <div class="desk-page">
  <PageHead title="用户与角色" en="Accounts" description="管理员维护账号。教师权限仍受负责课程约束，不能跨课访问。">
    <el-button type="primary" @click="creating = true">新增用户</el-button>
  </PageHead>
  <div class="meta">
    <span>当前登录 <b>{{ profile?.nickName }}</b></span>
    <span>账号 <b>{{ profile?.userName }}</b></span>
    <span>角色 <b>{{ roleText[profile?.role] || profile?.role }}</b></span>
  </div>
  <div class="sheet">
  <table class="data">
    <thead>
      <tr><th>姓名</th><th>账号</th><th>角色</th><th>课程范围</th><th>状态</th><th>操作</th></tr>
    </thead>
    <tbody>
      <tr v-for="row in rows" :key="row.account">
        <td>{{ row.name }}</td>
        <td>{{ row.account }}</td>
        <td>{{ row.role }}</td>
        <td>{{ row.scope }}</td>
        <td class="st-ok">启用</td>
        <td>
          <button class="link" type="button" @click="toast(row.role === '学生' ? '选课范围只读示例' : '打开编辑（示例，尚未写入）')">
            {{ row.role === '学生' ? '选课' : '编辑' }}
          </button>
        </td>
      </tr>
    </tbody>
  </table>
  </div>
  <p class="foot">上表是原型示例。当前登录身份来自认证接口。学生不能执行管理操作；跨课程读取应被拒绝。账号写入留到后续接口。</p>

  <el-dialog v-model="creating" title="新增用户" width="480px" class="glass-dialog" align-center>
    <div class="field"><label>姓名</label><el-input v-model="form.name" /></div>
    <div class="field"><label>邮箱</label><el-input v-model="form.email" /></div>
    <div class="field">
      <label>角色</label>
      <el-select v-model="form.role">
        <el-option label="学生" value="学生" />
        <el-option label="教师" value="教师" />
        <el-option label="系统管理员" value="系统管理员" />
      </el-select>
    </div>
    <div class="field">
      <label>负责课程（教师）</label>
      <el-select v-model="form.course">
        <el-option label="深度学习" value="深度学习" />
        <el-option label="计算机视觉" value="计算机视觉" />
      </el-select>
    </div>
    <template #footer>
      <el-button class="btn-ghost" @click="creating = false">取消</el-button>
      <el-button type="primary" @click="createUser">创建</el-button>
    </template>
  </el-dialog>
  </div>
</template>
