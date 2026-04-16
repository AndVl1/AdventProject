<script setup lang="ts">
import { ref } from 'vue'
import { useBooksStore } from '@/stores/books'
import StatusSelect from './StatusSelect.vue'
import type { Book, BookStatus } from '@/types'

defineProps<{
  books: Book[]
}>()

const booksStore = useBooksStore()
const updatingId = ref<number | null>(null)
const deletingId = ref<number | null>(null)

async function onStatusChange(id: number, status: BookStatus) {
  updatingId.value = id
  try {
    await booksStore.updateStatus(id, status)
  } finally {
    updatingId.value = null
  }
}

async function onDelete(id: number) {
  deletingId.value = id
  try {
    await booksStore.remove(id)
  } finally {
    deletingId.value = null
  }
}
</script>

<template>
  <div class="book-list">
    <p v-if="books.length === 0" class="empty-text">No books yet. Add your first book above.</p>
    <div v-for="book in books" :key="book.id" class="book-item">
      <div class="book-info">
        <span class="book-title">{{ book.title }}</span>
        <span class="book-author">by {{ book.author }}</span>
      </div>
      <div class="book-actions">
        <StatusSelect
          :model-value="book.status"
          :disabled="updatingId === book.id"
          @update:model-value="onStatusChange(book.id, $event)"
        />
        <button
          class="btn-delete"
          :disabled="deletingId === book.id"
          @click="onDelete(book.id)"
        >
          {{ deletingId === book.id ? '...' : 'Delete' }}
        </button>
      </div>
    </div>
  </div>
</template>
