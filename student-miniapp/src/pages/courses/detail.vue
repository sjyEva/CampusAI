<template>
  <view class="page">
    <view v-if="loading" class="muted">正在加载课程…</view>
    <view v-else-if="error" class="error">
      <view>{{ error }}</view>
      <button v-if="courseId" class="retry" size="mini" @tap="loadCourse">重新加载</button>
    </view>
    <view v-else-if="course" class="course-card">
      <view class="course-code">{{ course.courseCode }}</view>
      <view class="title">{{ course.name }}</view>
      <view v-if="course.description" class="muted course-description">{{ course.description }}</view>
      <view class="chapters">
        <view class="chapter-heading">课程章节</view>
        <view v-if="chapters.length === 0" class="muted">本课程暂无章节。</view>
        <view v-for="chapter in chapters" :key="chapter.id" class="chapter">
          {{ chapter.sortOrder }}. {{ chapter.title }}
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { getChapters, getCourse } from '../../api/courses.js'
import { getToken } from '../../utils/auth.js'

export default {
  data() {
    return { courseId: '', course: null, chapters: [], loading: false, error: '' }
  },
  onLoad(options) {
    if (!getToken()) {
      uni.reLaunch({ url: '/pages/login/index' })
      return
    }
    if (!/^[1-9]\d*$/.test(options?.id || '')) {
      this.error = '课程编号无效'
      return
    }
    this.courseId = options.id
    this.loadCourse()
  },
  methods: {
    async loadCourse() {
      if (this.loading) return
      this.loading = true
      this.error = ''
      try {
        const [detail, chapterList] = await Promise.all([
          getCourse(this.courseId),
          getChapters(this.courseId),
        ])
        if (!detail.data?.id || !Array.isArray(chapterList.data)) {
          throw new Error('课程或章节响应格式有误')
        }
        this.course = detail.data
        this.chapters = chapterList.data
      } catch (error) {
        if (error.code === 401 && !getToken()) {
          uni.reLaunch({ url: '/pages/login/index' })
          return
        }
        this.error = error.message
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.course-card {
  background: #fff;
  border: 1px solid #dce5ec;
  border-radius: 16rpx;
  padding: 30rpx;
}

.course-code {
  color: #215882;
  font-size: 25rpx;
  margin-bottom: 10rpx;
}

.course-description {
  margin-top: 20rpx;
  overflow-wrap: anywhere;
}

.chapters {
  margin-top: 24rpx;
  border-top: 1px solid #dce5ec;
  padding-top: 20rpx;
}

.chapter-heading {
  font-weight: 600;
  margin-bottom: 12rpx;
}

.chapter {
  padding: 8rpx 0;
  overflow-wrap: anywhere;
}

.retry {
  margin: 12rpx 0 0;
}
</style>
