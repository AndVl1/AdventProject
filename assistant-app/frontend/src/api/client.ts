import axios from 'axios'
import type {
  ChatRequest,
  ChatResponse,
  ModelsResponse,
  PromptsResponse,
  ToolsResponse,
} from './types'

// Базовый URL — всё идёт через прокси /api → localhost:8080
export const http = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
})

export const api = {
  sendMessage(req: ChatRequest): Promise<ChatResponse> {
    return http.post<ChatResponse>('/chat', req).then((r) => r.data)
  },

  getModels(): Promise<ModelsResponse> {
    return http.get<ModelsResponse>('/models').then((r) => r.data)
  },

  getTools(): Promise<ToolsResponse> {
    return http.get<ToolsResponse>('/tools').then((r) => r.data)
  },

  getPrompts(): Promise<PromptsResponse> {
    return http.get<PromptsResponse>('/prompts').then((r) => r.data)
  },

  clearSession(sessionId: string): Promise<void> {
    return http.delete(`/sessions/${sessionId}`).then(() => undefined)
  },
}
