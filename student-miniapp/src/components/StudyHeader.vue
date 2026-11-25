<template>
  <view class="study-nav" :style="{ paddingTop: status + 'px' }">
    <view class="nav" :style="{ minHeight: rowHeight + 'px' }">
      <view class="leading">
        <button v-if="back" class="study-text-btn back" aria-label="返回课程列表" @tap="goBack">‹</button>
        <button class="avatar" aria-label="打开个人中心" @tap="$emit('profile')">
          <image v-if="profile?.avatar" class="avatar-image" :src="profile.avatar" mode="aspectFill" />
          <text v-else>{{ initial }}</text>
        </button>
      </view>
      <view class="nav-title">
        <text class="name">{{ title }}</text>
        <text class="mode">{{ mode === 'live' ? '已连接后端' : '演示数据' }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import { navigationMetrics } from '../utils/study-ui.js'

const props = defineProps({
  title: { type: String, required: true },
  mode: { type: String, default: 'demo' },
  profile: { type: Object, default: () => ({ nickname: '同学', avatar: '' }) },
  back: Boolean
})
defineEmits(['profile'])
const { status, rowHeight } = navigationMetrics()
const initial = computed(() => Array.from(props.profile?.nickname || '同学')[0])

function goBack() {
  uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/courses/index' }) })
}
</script>
