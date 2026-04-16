import { defineStore } from 'pinia'
import { ref } from 'vue'
import { booksApi } from '@/services/booksApi'
import type { Book, BookStats, BookStatus, CreateBookRequest } from '@/types'

export const useBooksStore = defineStore('books', () => {
  const books = ref<Book[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchAll(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      books.value = await booksApi.getAll()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load books'
    } finally {
      loading.value = false
    }
  }

  async function create(data: CreateBookRequest): Promise<void> {
    const book = await booksApi.create(data)
    books.value.push(book)
  }

  async function updateStatus(id: number, status: BookStatus): Promise<void> {
    const updated = await booksApi.updateStatus(id, { status })
    const index = books.value.findIndex((b) => b.id === id)
    if (index !== -1) {
      books.value[index] = updated
    }
  }

  async function remove(id: number): Promise<void> {
    await booksApi.remove(id)
    books.value = books.value.filter((b) => b.id !== id)
  }

  async function fetchStats(): Promise<BookStats> {
    return booksApi.stats()
  }

  return { books, loading, error, fetchAll, create, updateStatus, remove, fetchStats }
})
