<template>
  <view class="page">
    <view class="heading">
      <view>
        <view class="title">我的课程</view>
        <view v-if="studentName" class="muted student-name">{{ studentName }}</view>
      </view>
      <button size="mini" :disabled="signingOut" @tap="signOut">退出登录</button>
    </view>

    <view v-if="loading" class="muted state">正在加载课程…</view>
    <view v-else-if="error" class="state">
      <view class="error">{{ error }}</view>
      <button size="mini" @tap="loadCourses">重新加载</button>
    </view>
    <view v-else-if="!courses.length" class="muted state">暂无可访问的课程</view>
    <view v-else>
      <navigator
        v-for="course in courses"
        :key="course.id"
        :url="'/pages/courses/detail?id=' + course.id"
        class="course-card"
      >
        <view class="course-name">{{ course.name }}</view>
        <view class="course-code">{{ course.courseCode }}</view>
        <view v-if="course.description" class="muted course-description">{{ course.description }}</view>
      </navigator>
    </view>
  </view>
</template>

<script>
import { getCourses } from '../../api/courses.js'
import { getInfo, logout } from '../../api/login.js'
import { getToken, removeToken } from '../../utils/auth.js'

function openLogin() {
  uni.reLaunch({ url: '/pages/login/index' })
}

export default {
  data() {
    return { courses: [], studentName: '', loading: false, signingOut: false, error: '' }
  },
  onLoad() {
    if (!getToken()) {
      openLogin()
      return
    }
    this.loadCourses()
  },
  methods: {
    async loadCourses() {
      if (this.loading) return
      this.loading = true
      this.error = ''
      try {
        const [profile, result] = await Promise.all([getInfo(), getCourses()])
        if (!Array.isArray(result.data)) throw new Error('课程列表响应格式有误')
        this.studentName = profile.user?.nickName || profile.user?.userName || ''
        this.courses = result.data
      } catch (error) {
        if (error.code === 401 && !getToken()) {
          openLogin()
          return
        }
        this.error = error.message
      } finally {
        this.loading = false
      }
    },
    async signOut() {
      if (this.signingOut) return
      this.signingOut = true
      try {
        await logout()
      } catch {
        // Local logout also works when the backend is temporarily unreachable.
      } finally {
        removeToken()
        openLogin()
      }
    }
  }
}
</script>

<style scoped>
.heading {
  align-items: center;
  display: flex;
  justify-content: space-between;
  margin-bottom: 24rpx;
}

.heading button {
  margin: 0;
}

.student-name {
  font-size: 26rpx;
  margin-top: 8rpx;
}

.state {
  padding: 34rpx 0;
}

.course-card {
  background: var(--campus-surface);
  border: 1px solid var(--campus-line);
  border-radius: 16rpx;
  margin-bottom: 20rpx;
  padding: 26rpx;
}

.course-name {
  font-size: 34rpx;
  font-weight: 650;
}

.course-code {
  color: var(--campus-accent);
  font-size: 25rpx;
  margin-top: 6rpx;
}

.course-description {
  margin-top: 12rpx;
  overflow-wrap: anywhere;
}
</style>
