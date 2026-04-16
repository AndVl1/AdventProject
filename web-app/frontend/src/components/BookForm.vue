<script setup lang="ts">
import { ref } from 'vue'
import { useBooksStore } from '@/stores/books'
import StatusSelect from './StatusSelect.vue'
import type { BookStatus } from '@/types'

const booksStore = useBooksStore()

const title = ref('')
const author = ref('')
const status = ref<BookStatus>('WANT_TO_READ')
const submitting = ref(false)
const formError = ref<string | null>(null)

async function handleSubmit() {
  if (!title.value.trim() || !author.value.trim()) {
    formError.value = 'Title and author are required'
    return
  }

  submitting.value = true
  formError.value = null

  try {
    await booksStore.create({
      title: title.value.trim(),
      author: author.value.trim(),
      status: status.value,
    })
    title.value = ''
    author.value = ''
    status.value = 'WANT_TO_READ'
  } catch (e) {
    formError.value = e instanceof Error ? e.message : 'Failed to add book'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <form class="book-form" @submit.prevent="handleSubmit">
    <h3>Add a book</h3>
    <div class="form-group">
      <input v-model="title" type="text" placeholder="Title" required />
    </div>
    <div class="form-group">
      <input v-model="author" type="text" placeholder="Author" required />
    </div>
    <div class="form-group">
      <StatusSelect v-model="status" />
    </div>
    <p v-if="formError" class="error-text">{{ formError }}</p>
    <button type="submit" :disabled="submitting">
      {{ submitting ? 'Adding...' : 'Add Book' }}
    </button>
  </form>
</template>
