<template>
  <view class="study-page study-note">{{ message }}</view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'

const message = ref('正在打开课程问答…')
onLoad(options => {
  if (!/^[1-9]\d*$/.test(options?.id || '')) {
    message.value = '课程编号无效'
    return
  }
  uni.redirectTo({ url: '/pages/courses/detail?id=' + options.id + '&ask=1' })
})
</script>
