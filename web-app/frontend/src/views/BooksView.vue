<script setup lang="ts">
import { onMounted } from 'vue'
import { useBooksStore } from '@/stores/books'
import BookForm from '@/components/BookForm.vue'
import BookList from '@/components/BookList.vue'

const booksStore = useBooksStore()

onMounted(() => {
  void booksStore.fetchAll()
})
</script>

<template>
  <div class="page">
    <h1>My Books</h1>
    <BookForm />
    <div v-if="booksStore.loading" class="loading-text">Loading...</div>
    <p v-else-if="booksStore.error" class="error-text">{{ booksStore.error }}</p>
    <BookList v-else :books="booksStore.books" />
  </div>
</template>
