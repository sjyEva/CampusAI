import { createRouter, createWebHashHistory } from 'vue-router'
import AdminLayout from './layouts/AdminLayout.vue'
import { profile, restoreSession } from './session.js'
import CoursesView from './views/CoursesView.vue'
import DocsView from './views/DocsView.vue'
import LoginView from './views/LoginView.vue'
import OverviewView from './views/OverviewView.vue'
import QaView from './views/QaView.vue'
import StatsView from './views/StatsView.vue'
import QuizView from './views/QuizView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    {
      path: '/',
      component: AdminLayout,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: { name: 'overview' } },
        { path: 'overview', name: 'overview', component: OverviewView },
        { path: 'courses', name: 'courses', component: CoursesView },
        { path: 'docs', name: 'docs', component: DocsView },
        { path: 'quiz', name: 'quiz', component: QuizView },
        { path: 'qa', name: 'qa', component: QaView },
        { path: 'stats', name: 'stats', component: StatsView },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: { name: 'overview' } },
  ],
})

router.beforeEach(async (to) => {
  const authenticated = await restoreSession()
  if (to.name === 'login') return authenticated ? { name: 'overview' } : true
  if (to.matched.some((record) => record.meta.requiresAuth) && !authenticated) return { name: 'login' }
  if (to.meta.admin && profile.value?.role !== 'admin') return { name: 'overview', query: { denied: '1' } }
  return true
})

export default router
