export type BookStatus = 'WANT_TO_READ' | 'READING' | 'READ'

export interface Book {
  id: number
  title: string
  author: string
  status: BookStatus
  createdAt: string
}

export interface CreateBookRequest {
  title: string
  author: string
  status: BookStatus
}

export interface UpdateStatusRequest {
  status: BookStatus
}

export interface BookStats {
  wantToRead: number
  reading: number
  read: number
  total: number
}

export interface TokenResponse {
  token: string
}

export interface AuthRequest {
  username: string
  password: string
}

export interface ApiError {
  error: string
  message: string
}
