<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useBooksStore } from '@/stores/books'
import type { BookStats } from '@/types'

const booksStore = useBooksStore()
const stats = ref<BookStats | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    stats.value = await booksStore.fetchStats()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load stats'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page">
    <h1>Statistics</h1>
    <div v-if="loading" class="loading-text">Loading...</div>
    <p v-else-if="error" class="error-text">{{ error }}</p>
    <div v-else-if="stats" class="stats-grid">
      <div class="stat-card">
        <div class="stat-value">{{ stats.wantToRead }}</div>
        <div class="stat-label">Want to read</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ stats.reading }}</div>
        <div class="stat-label">Reading</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ stats.read }}</div>
        <div class="stat-label">Read</div>
      </div>
      <div class="stat-card stat-card--total">
        <div class="stat-value">{{ stats.total }}</div>
        <div class="stat-label">Total</div>
      </div>
    </div>
  </div>
</template>
