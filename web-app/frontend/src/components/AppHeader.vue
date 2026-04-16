<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const router = useRouter()

async function handleLogout() {
  authStore.logout()
  await router.push('/login')
}
</script>

<template>
  <header v-if="authStore.isAuthenticated" class="app-header">
    <div class="header-brand">Bookshelf</div>
    <nav class="header-nav">
      <router-link to="/books">Books</router-link>
      <router-link to="/stats">Stats</router-link>
    </nav>
    <div class="header-user">
      <span class="username">{{ authStore.username }}</span>
      <button @click="handleLogout">Logout</button>
    </div>
  </header>
</template>
