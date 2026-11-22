<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCodeImg } from '../api/login.js'
import { signIn } from '../session.js'

const router = useRouter()
const username = ref('')
const password = ref('')
const code = ref('')
const captcha = ref(null)
const loadingCaptcha = ref(false)
const captchaError = ref('')
const submitting = ref(false)
const errorMessage = ref('')

async function loadCaptcha() {
  if (loadingCaptcha.value) return
  loadingCaptcha.value = true
  captcha.value = null
  captchaError.value = ''
  code.value = ''
  try {
    const config = await getCodeImg()
    captcha.value = {
      enabled: config.captchaEnabled ?? true,
      image: config.img,
      uuid: config.uuid,
    }
  } catch (error) {
    captchaError.value = error.message
  } finally {
    loadingCaptcha.value = false
  }
}

async function submit() {
  if (submitting.value || !captcha.value) return
  errorMessage.value = ''
  submitting.value = true
  try {
    const verificationCode = captcha.value.enabled ? code.value.trim() : undefined
    await signIn(username.value, password.value, verificationCode, captcha.value.uuid)
    await router.replace({ name: 'courses' })
  } catch (error) {
    errorMessage.value = error.message
    if (captcha.value?.enabled) await loadCaptcha()
  } finally {
    submitting.value = false
  }
}

onMounted(loadCaptcha)
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
        <template v-if="captcha?.enabled">
          <label for="code">验证码</label>
          <div class="captcha-row">
            <input id="code" v-model.trim="code" autocomplete="off" required />
            <button type="button" class="captcha-image" aria-label="刷新验证码" :disabled="submitting" @click="loadCaptcha">
              <img :src="`data:image/png;base64,${captcha.image}`" alt="登录验证码" />
            </button>
          </div>
        </template>
        <p v-if="loadingCaptcha" class="login-status" role="status">正在连接认证服务…</p>
        <template v-if="captchaError">
          <p class="error" role="alert">{{ captchaError }}</p>
          <button type="button" class="secondary-button" @click="loadCaptcha">重新连接</button>
        </template>
        <button class="primary-button" type="submit" :disabled="submitting || !captcha">
          {{ submitting ? '正在登录…' : '登录' }}
        </button>
        <p v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
      </form>
    </section>
  </main>
</template>
