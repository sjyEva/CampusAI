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
    await router.replace({ name: 'overview' })
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
  <div class="login-screen">
    <form class="login" @submit.prevent="submit">
      <div class="brand-mark">CA</div>
      <h1>CampusAI 管理后台</h1>
      <p class="sub">教师维护课程内容；管理员维护账号与权限。</p>
      <div class="field">
        <label for="loginUser">账号</label>
        <el-input id="loginUser" v-model="username" autocomplete="username" placeholder="教师或管理员账号" />
      </div>
      <div class="field">
        <label for="loginPassword">密码</label>
        <el-input id="loginPassword" v-model="password" type="password" show-password autocomplete="current-password" />
      </div>
      <template v-if="captcha?.enabled">
        <div class="field">
          <label for="loginCode">验证码</label>
          <div class="captcha-row">
            <el-input id="loginCode" v-model="code" autocomplete="off" />
            <button type="button" class="captcha-image" aria-label="刷新验证码" :disabled="submitting" @click="loadCaptcha">
              <img :src="`data:image/png;base64,${captcha.image}`" alt="登录验证码" />
            </button>
          </div>
        </div>
      </template>
      <p v-if="loadingCaptcha" class="note">正在连接认证服务…</p>
      <p v-if="captchaError" class="error" role="alert">{{ captchaError }}</p>
      <div class="btn-row">
        <el-button v-if="captchaError" class="btn-ghost" @click="loadCaptcha">重新连接</el-button>
        <el-button type="primary" native-type="submit" :disabled="submitting || !captcha" :loading="submitting">
          {{ submitting ? '正在登录…' : '登录' }}
        </el-button>
      </div>
      <p v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
      <p class="note">登录后按返回角色进入。教师看不到用户管理；直接打开管理员地址也会被拦下。<br>演示账号 admin、teacher，密码 CampusAI-Dev-2026。</p>
    </form>
  </div>
</template>
