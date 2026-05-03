import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import AdminGatewayView from '@/views/AdminGatewayView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'chat',
      component: ChatView,
    },
    {
      path: '/admin/gateway',
      name: 'admin-gateway',
      component: AdminGatewayView,
    },
    // Wildcard — всё остальное редиректим на главную
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

export default router
