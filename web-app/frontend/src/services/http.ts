import axios from 'axios'
import type { AxiosInstance } from 'axios'
import { getActivePinia } from 'pinia'
import type { Pinia } from 'pinia'

const baseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

export const http: AxiosInstance = axios.create({
  baseURL,
  headers: {
    'Content-Type': 'application/json',
  },
})

type AuthPiniaState = { token: string | null; username: string | null }

function getAuthState(): AuthPiniaState | undefined {
  const pinia = getActivePinia() as Pinia | undefined
  return pinia?.state.value['auth'] as AuthPiniaState | undefined
}

http.interceptors.request.use((config) => {
  const token = getAuthState()?.token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const status =
      typeof error === 'object' &&
      error !== null &&
      'response' in error &&
      typeof (error as { response?: { status?: number } }).response?.status === 'number'
        ? (error as { response: { status: number } }).response.status
        : null

    if (status === 401) {
      const authState = getAuthState()
      if (authState) {
        authState.token = null
        authState.username = null
        localStorage.removeItem('bookshelf.token')
        localStorage.removeItem('bookshelf.username')
      }
      window.location.assign('/login')
    }
    return Promise.reject(error)
  },
)
