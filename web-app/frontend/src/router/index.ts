import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import BooksView from '@/views/BooksView.vue'
import StatsView from '@/views/StatsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
    },
    {
      path: '/register',
      name: 'register',
      component: RegisterView,
    },
    {
      path: '/books',
      name: 'books',
      component: BooksView,
      meta: { requiresAuth: true },
    },
    {
      path: '/stats',
      name: 'stats',
      component: StatsView,
      meta: { requiresAuth: true },
    },
    {
      path: '/',
      redirect: () => {
        const token = localStorage.getItem('bookshelf.token')
        return token ? '/books' : '/login'
      },
    },
  ],
})

router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('bookshelf.token')
  const requiresAuth = to.meta.requiresAuth as boolean | undefined

  if (requiresAuth && !token) {
    next('/login')
  } else if (token && (to.name === 'login' || to.name === 'register')) {
    next('/books')
  } else {
    next()
  }
})

export default router
