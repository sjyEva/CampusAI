import { createRouter, createWebHashHistory } from 'vue-router'
import AdminLayout from './layouts/AdminLayout.vue'
import { profile, restoreSession } from './session.js'
import LoginView from './views/LoginView.vue'
import OverviewView from './views/OverviewView.vue'

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
