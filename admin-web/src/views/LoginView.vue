<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { signIn } from '../session.js'

const router = useRouter()
const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

async function submit() {
  if (submitting.value) return
  errorMessage.value = ''
  submitting.value = true
  try {
    await signIn(username.value, password.value)
    await router.replace({ name: 'courses' })
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card" aria-labelledby="login-title">
      <div class="login-cover">
        <div class="brand"><span class="brand-mark" aria-hidden="true">C</span> CampusAI</div>
        <h1 id="login-title">课程管理<br />工作台</h1>
        <p>校园 AI 课程知识助手</p>
      </div>
      <form class="login-form" @submit.prevent="submit">
        <div>
          <h2>登录管理后台</h2>
          <p>使用教师或管理员账号登录。</p>
        </div>
        <label for="username">账号</label>
        <input id="username" v-model.trim="username" autocomplete="username" required />
        <label for="password">密码</label>
        <input id="password" v-model="password" type="password" autocomplete="current-password" required />
        <button class="primary-button" type="submit" :disabled="submitting">
          {{ submitting ? '正在登录…' : '登录' }}
        </button>
        <p v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
      </form>
    </section>
  </main>
</template>
