<script setup>
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { profile, signOut } from './session.js'

const router = useRouter()
const signingOut = ref(false)
const roleLabel = computed(() => profile.value?.role === 'admin' ? '管理员' : '教师')
const courseLabel = computed(() => profile.value?.role === 'admin' ? '全部课程' : '负责课程')

async function handleSignOut() {
  signingOut.value = true
  try {
    await signOut()
  } catch {
    // Local credentials are removed even if the server cannot be reached.
  } finally {
    signingOut.value = false
    await router.replace({ name: 'login' })
  }
}
</script>

<template>
  <div v-if="profile" class="workspace">
    <header class="topbar">
      <RouterLink class="brand" :to="{ name: 'courses' }">
        <span class="brand-mark" aria-hidden="true">C</span>
        <span>CampusAI <small>管理后台</small></span>
      </RouterLink>
      <div class="topbar-account">
        <span class="account-name">{{ profile.nickName }} · {{ roleLabel }}</span>
        <button class="quiet-button" type="button" :disabled="signingOut" @click="handleSignOut">
          {{ signingOut ? '正在退出…' : '退出登录' }}
        </button>
      </div>
    </header>
    <div class="workspace-body">
      <nav class="sidebar" aria-label="管理后台导航">
        <RouterLink :to="{ name: 'courses' }" :class="{ 'router-link-active': $route.name === 'course' }">
          {{ courseLabel }}
        </RouterLink>
        <RouterLink :to="{ name: 'account' }">
          {{ profile.role === 'admin' ? '管理账号' : '教学账号' }}
        </RouterLink>
      </nav>
      <main class="page">
        <RouterView />
      </main>
    </div>
  </div>
  <RouterView v-else-if="$route.name === 'login'" />
</template>
