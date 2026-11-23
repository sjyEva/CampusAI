import { createRouter, createWebHashHistory } from 'vue-router'
import AdminLayout from './layouts/AdminLayout.vue'
import { profile, restoreSession } from './session.js'
import LoginView from './views/LoginView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    {
      path: '/',
      component: AdminLayout,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: { name: 'login' } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: { name: 'login' } },
  ],
})

router.beforeEach(async (to) => {
  const authenticated = await restoreSession()
  if (to.name === 'login') return true
  if (to.matched.some((record) => record.meta.requiresAuth) && !authenticated) return { name: 'login' }
  if (to.meta.admin && profile.value?.role !== 'admin') return { name: 'login' }
  return true
})

export default router
