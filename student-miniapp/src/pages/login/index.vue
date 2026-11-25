<template>
  <view class="page">
    <view class="login-card">
      <view class="title">CampusAI</view>
      <view class="muted subtitle">校园 AI 课程知识助手</view>

      <view class="field">
        <text>账号</text>
        <input v-model="username" placeholder="请输入账号" />
      </view>
      <view class="field">
        <text>密码</text>
        <input v-model="password" password placeholder="请输入密码" />
      </view>
      <view v-if="captchaEnabled" class="field">
        <text>验证码</text>
        <view class="captcha-row">
          <input v-model="code" placeholder="请输入验证码" @confirm="signIn" />
          <image v-if="captchaImage" :src="captchaImage" mode="aspectFit" @tap="loadCaptcha" />
        </view>
      </view>

      <view v-if="error" class="error">{{ error }}</view>
      <button v-if="!captchaReady && !captchaLoading" @tap="loadCaptcha">重试连接</button>
      <button class="primary" :loading="submitting" :disabled="submitting || !captchaReady" @tap="signIn">
        登录
      </button>
      <button class="study-text-btn demo-link" :disabled="submitting" @tap="openDemo">返回演示</button>
    </view>
  </view>
</template>

<script>
import { getCodeImg, login } from '../../api/login.js'
import { getToken, setToken } from '../../utils/auth.js'
import { signOut, studentSession } from '../../api/session.js'

export default {
  data() {
    return {
      username: '',
      password: '',
      code: '',
      uuid: '',
      captchaImage: '',
      captchaEnabled: false,
      captchaReady: false,
      captchaLoading: false,
      submitting: false,
      error: ''
    }
  },
  onLoad() {
    if (getToken()) {
      uni.reLaunch({ url: '/pages/courses/index' })
      return
    }
    this.loadCaptcha()
  },
  methods: {
    openDemo() {
      uni.reLaunch({ url: '/pages/courses/index' })
    },
    async loadCaptcha(keepError = false) {
      if (this.captchaLoading) return
      this.captchaLoading = true
      this.captchaReady = false
      if (keepError !== true) this.error = ''

      try {
        const result = await getCodeImg()
        this.captchaEnabled = result.captchaEnabled !== false
        this.code = ''
        this.uuid = result.uuid || ''
        this.captchaImage = result.img ? `data:image/gif;base64,${result.img}` : ''
        this.captchaReady = true
      } catch (error) {
        this.error = error.message
      } finally {
        this.captchaLoading = false
      }
    },
    async signIn() {
      if (this.submitting || !this.captchaReady) return
      const username = this.username.trim()
      if (!username || !this.password) {
        this.error = '请输入账号和密码'
        return
      }
      if (this.captchaEnabled && !this.code.trim()) {
        this.error = '请输入验证码'
        return
      }

      const credentials = { username, password: this.password }
      if (this.captchaEnabled) {
        credentials.code = this.code.trim()
        credentials.uuid = this.uuid
      }

      this.error = ''
      this.submitting = true
      try {
        const result = await login(credentials)
        if (typeof result.token !== 'string' || !result.token) throw new Error('登录响应缺少凭证')
        setToken(result.token)
        try { await studentSession() } catch (error) {
          await signOut()
          throw error
        }
        uni.reLaunch({ url: '/pages/courses/index' })
      } catch (error) {
        this.error = error.message
        if (this.captchaEnabled) await this.loadCaptcha(true)
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>

<style scoped>
.login-card {
  background: var(--campus-surface);
  border: 1px solid var(--campus-line);
  border-radius: 20rpx;
  margin-top: 64rpx;
  padding: 36rpx;
}

.subtitle {
  margin: 12rpx 0 40rpx;
}

.demo-link { display: flex; margin: 12rpx auto 0; }

.field {
  margin: 28rpx 0;
}

.field input {
  border: 1px solid var(--campus-line-strong);
  border-radius: 10rpx;
  height: 84rpx;
  margin-top: 10rpx;
  padding: 0 20rpx;
}

.captcha-row {
  align-items: center;
  display: flex;
  gap: 14rpx;
}

.captcha-row input {
  flex: 1;
}

.captcha-row image {
  width: 185rpx;
  height: 84rpx;
  margin-top: 10rpx;
}
</style>
