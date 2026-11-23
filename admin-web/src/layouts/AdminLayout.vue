<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { resetCourseScope, useCourseScope } from '../composables/courseScope.js'
import { profile, signOut } from '../session.js'
import { redirectIfUnauthorized } from '../utils/authError.js'
import { toast } from '../utils/toast.js'

const route = useRoute()
const router = useRouter()
const { load } = useCourseScope()
const leaving = ref(false)

const groups = computed(() => {
  const items = [
    {
      label: '内容',
      en: 'Content',
      entries: [
        { name: 'overview', title: '工作台' },
        { name: 'courses', title: '课程与章节' },
        { name: 'docs', title: '资料管理' },
        { name: 'quiz', title: '题库维护' },
      ],
    },
    {
      label: '反馈',
      en: 'Feedback',
      entries: [
        { name: 'qa', title: '问答与反馈' },
        { name: 'stats', title: '学习统计' },
      ],
    },
    {
      label: '平台',
      en: 'Platform',
      admin: true,
      entries: [{ name: 'users', title: '用户与角色' }],
    },
  ]
  if (profile.value?.role === 'admin') return items
  return items.filter((group) => !group.admin)
})

const roleLabel = computed(() => (profile.value?.role === 'admin' ? '系统管理员' : '教师'))
const avatar = computed(() => (profile.value?.nickName || '用').slice(0, 1))

watch(() => route.query.denied, (denied) => {
  if (!denied) return
  toast('没有访问权限，已返回工作台')
  router.replace({ name: 'overview' })
}, { immediate: true })

onMounted(async () => {
  try {
    await load()
  } catch (error) {
    await redirectIfUnauthorized(error, router)
  }
})

async function handleSignOut() {
  if (leaving.value) return
  leaving.value = true
  try {
    await signOut()
  } catch {
    // Local credentials are removed even if the server cannot be reached.
  } finally {
    resetCourseScope()
    leaving.value = false
    await router.replace({ name: 'login' })
  }
}
</script>

<template>
  <div class="admin-root">
    <div class="shell">
      <aside class="side">
        <div class="brand">
          <div class="brand-copy">
            <div class="brand-name">CampusAI</div>
            <div class="brand-sub">管理后台</div>
          </div>
        </div>
        <template v-for="group in groups" :key="group.label">
          <div class="nav-label">
            <span>{{ group.label }}</span>
            <span class="nav-en">{{ group.en }}</span>
          </div>
          <RouterLink
            v-for="item in group.entries"
            :key="item.name"
            class="nav"
            active-class="active"
            :to="{ name: item.name }"
          >
            {{ item.title }}
          </RouterLink>
        </template>
        <div class="side-foot">
          <span class="avatar">{{ avatar }}</span>
          <div class="who-text">
            <div class="meta-name">{{ profile?.nickName }}</div>
            <div class="meta-role">{{ roleLabel }}</div>
          </div>
          <button class="wire" type="button" :disabled="leaving" @click="handleSignOut">
            {{ leaving ? '退出中' : '退出' }}
          </button>
        </div>
      </aside>
      <section class="main">
        <div class="content">
          <RouterView />
        </div>
      </section>
    </div>
  </div>
</template>
