import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { authApi } from '@/services/authApi'
import type { AuthRequest } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('bookshelf.token'))
  const username = ref<string | null>(localStorage.getItem('bookshelf.username'))

  const isAuthenticated = computed(() => !!token.value)

  watch(token, (val) => {
    if (val) {
      localStorage.setItem('bookshelf.token', val)
    } else {
      localStorage.removeItem('bookshelf.token')
    }
  })

  watch(username, (val) => {
    if (val) {
      localStorage.setItem('bookshelf.username', val)
    } else {
      localStorage.removeItem('bookshelf.username')
    }
  })

  async function login(data: AuthRequest): Promise<void> {
    const response = await authApi.login(data)
    token.value = response.token
    username.value = data.username
  }

  async function register(data: AuthRequest): Promise<void> {
    const response = await authApi.register(data)
    token.value = response.token
    username.value = data.username
  }

  function logout(): void {
    token.value = null
    username.value = null
  }

  return { token, username, isAuthenticated, login, register, logout }
})
