import { http } from './http'
import type { Book, BookStats, CreateBookRequest, UpdateStatusRequest } from '@/types'

export const booksApi = {
  getAll(): Promise<Book[]> {
    return http.get<Book[]>('/api/books').then((r) => r.data)
  },

  create(data: CreateBookRequest): Promise<Book> {
    return http.post<Book>('/api/books', data).then((r) => r.data)
  },

  updateStatus(id: number, data: UpdateStatusRequest): Promise<Book> {
    return http.patch<Book>(`/api/books/${id}/status`, data).then((r) => r.data)
  },

  remove(id: number): Promise<void> {
    return http.delete(`/api/books/${id}`).then(() => undefined)
  },

  stats(): Promise<BookStats> {
    return http.get<BookStats>('/api/books/stats').then((r) => r.data)
  },
}
