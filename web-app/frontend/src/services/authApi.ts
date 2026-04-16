import { http } from './http'
import type { AuthRequest, TokenResponse } from '@/types'

export const authApi = {
  login(data: AuthRequest): Promise<TokenResponse> {
    return http.post<TokenResponse>('/api/auth/login', data).then((r) => r.data)
  },

  register(data: AuthRequest): Promise<TokenResponse> {
    return http.post<TokenResponse>('/api/auth/register', data).then((r) => r.data)
  },
}
