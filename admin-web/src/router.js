import { createRouter, createWebHashHistory } from 'vue-router'
import { restoreSession } from './session.js'
import AccountView from './views/AccountView.vue'
import CourseView from './views/CourseView.vue'
import CoursesView from './views/CoursesView.vue'
import LoginView from './views/LoginView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    { path: '/courses', name: 'courses', component: CoursesView, meta: { protected: true } },
    { path: '/courses/:id(\\d+)', name: 'course', component: CourseView, meta: { protected: true } },
    { path: '/account', name: 'account', component: AccountView, meta: { protected: true } },
    { path: '/:pathMatch(.*)*', redirect: { name: 'courses' } },
  ],
})

router.beforeEach(async (route) => {
  const authenticated = await restoreSession()
  if (route.meta.protected && !authenticated) return { name: 'login' }
  if (route.name === 'login' && authenticated) return { name: 'courses' }
})

export default router
