<template>
  <view class="study-drawer">
    <view class="shade" @tap="$emit('close')" />
    <scroll-view scroll-y class="drawer" :style="{ paddingTop: status + 'px' }">
      <view class="drawer-content">
        <view class="drawer-title">
          <button v-if="current !== 'home'" class="study-text-btn drawer-back" aria-label="返回个人中心" @tap="back">‹ 返回</button>
          <text class="study-heading">{{ labels[current] }}</text>
        </view>
        <template v-if="current === 'home'">
          <view class="identity">
            <view class="photo">
              <image v-if="workspace.profile.avatar" class="photo-image" :src="workspace.profile.avatar" mode="aspectFill" />
              <text v-else>{{ initial }}</text>
            </view>
            <view class="identity-copy">
              <view class="study-subtitle">{{ workspace.profile.nickname }}</view>
              <view class="study-caption">{{ session.mode === 'live' ? '学生账号' : '演示账号' }}</view>
            </view>
          </view>
          <button v-for="item in menu" :key="item.id" class="menu-row" @tap="openView(item.id)">
            <text>{{ labels[item.id] }}</text><view class="study-arrow" />
          </button>
          <button class="menu-row" :disabled="leaving" @tap="changeConnection">
            <text>{{ session.mode === 'live' ? '退出登录' : '连接后端' }}</text><view class="study-arrow" />
          </button>
        </template>
        <template v-else-if="current === 'profile'">
          <view class="study-stack">
            <view>昵称</view>
            <input v-model="nickname" class="study-field" maxlength="20" placeholder="填写昵称" />
            <view class="study-actions">
              <view class="photo" v-if="avatar"><image class="photo-image" :src="avatar" mode="aspectFill" /></view>
              <button class="study-btn" @tap="selectAvatar">更换头像</button>
            </view>
            <button class="study-btn is-primary" @tap="saveIdentity">保存修改</button>
          </view>
        </template>
        <template v-else-if="current === 'favorites' || current === 'library'">
          <input v-model="queries[current]" class="study-field search" confirm-type="search"
            :aria-label="current === 'library' ? '搜索个人资料' : '搜索收藏'"
            :placeholder="current === 'library' ? '搜索个人资料' : '搜索收藏'" />
          <button v-if="current === 'library'" class="study-btn import" @tap="newEntry('file')">导入资料</button>
          <view v-if="!matching.length" class="study-note">{{ queries[current].trim() ? '没有匹配结果' : '暂无资料或笔记' }}</view>
          <MaterialList v-else compact :items="matching" :favorites="workspace.favorites" @favorite="favorite" />
        </template>
        <template v-else-if="current === 'feedback'">
          <view v-if="!workspace.feedback.length" class="study-note">暂无反馈记录</view>
          <button v-for="item in workspace.feedback" :key="item.id" class="menu-row feedback-row" @tap="viewFeedback(item)">
            <view>
              <view>{{ item.courseName }}</view>
              <view class="study-caption">{{ item.kind }} · {{ localDate(item.createdAt) }}</view>
            </view>
            <view class="study-arrow" />
          </button>
        </template>
        <template v-else-if="current === 'feedback-detail' && selectedFeedback">
          <view class="study-subtitle">{{ selectedFeedback.courseName }}</view>
          <view class="study-note">{{ selectedFeedback.kind }} · {{ localDate(selectedFeedback.createdAt) }} · 本机记录</view>
          <view class="study-text">{{ selectedFeedback.text }}</view>
        </template>
      </view>
    </scroll-view>
  </view>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import MaterialList from './MaterialList.vue'
import { signOut } from '../api/session.js'
import { favoriteEntries, localDate, saveProfile, searchEntries, setFavorite } from '../api/workspace.js'
import { chooseAvatar } from '../utils/files.js'
import { navigate, navigationMetrics, newEntry, tell } from '../utils/study-ui.js'

const props = defineProps({
  session: { type: Object, required: true },
  workspace: { type: Object, required: true }
})
const emit = defineEmits(['close', 'change'])
const labels = {
  home: '个人中心', profile: '编辑个人信息', favorites: '我的收藏', library: '个人资料库',
  feedback: '过往反馈', 'feedback-detail': '反馈详情'
}
const menu = [{ id: 'profile' }, { id: 'favorites' }, { id: 'library' }, { id: 'feedback' }]
const current = ref('home')
const nickname = ref('')
const avatar = ref('')
const leaving = ref(false)
const selectedFeedback = ref(null)
const queries = reactive({ favorites: '', library: '' })
const initial = computed(() => Array.from(props.workspace.profile.nickname || '同学')[0])
const matching = computed(() => searchEntries(
  current.value === 'favorites' ? favoriteEntries(props.workspace) : props.workspace.library,
  queries[current.value] || ''
))
const { status } = navigationMetrics()

function openView(view) {
  if (view === 'profile') {
    nickname.value = props.workspace.profile.nickname
    avatar.value = props.workspace.profile.avatar
  }
  current.value = view
}

function back() {
  current.value = current.value === 'feedback-detail' ? 'feedback' : 'home'
}

function viewFeedback(item) {
  selectedFeedback.value = item
  current.value = 'feedback-detail'
}

async function selectAvatar() {
  try { avatar.value = await chooseAvatar() } catch (error) { tell(error) }
}

function saveIdentity() {
  try {
    emit('change', saveProfile(props.session, { nickname: nickname.value, avatar: avatar.value }))
    current.value = 'home'
  } catch (error) { tell(error) }
}

function favorite(entry, enabled) {
  try { emit('change', setFavorite(props.session, entry, enabled)) } catch (error) { tell(error) }
}

async function changeConnection() {
  if (props.session.mode === 'demo') {
    emit('close')
    return navigate('/pages/login/index?connect=1')
  }
  if (leaving.value) return
  leaving.value = true
  await signOut()
  uni.reLaunch({ url: '/pages/courses/index' })
}
</script>
