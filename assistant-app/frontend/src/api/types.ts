// DTO-типы, которые точно соответствуют контракту бэкенда

export interface Model {
  id: string
  label: string
  provider: string
}

export interface Tool {
  id: string
  label: string
  description: string
}

export interface ToolCall {
  name: string
  args: Record<string, unknown>
  result: string
  ok: boolean
}

export interface ChatRequest {
  message: string
  sessionId?: string
  model: string
  enabledTools: string[]
}

export interface ChatResponse {
  sessionId: string
  reply: string
  model: string
  toolCalls: ToolCall[]
}

export interface ModelsResponse {
  models: Model[]
}

export interface ToolsResponse {
  tools: Tool[]
}

// Внутренние типы приложения (не DTO)

export type ChatMessage =
  | { role: 'user'; text: string }
  | { role: 'assistant'; text: string; toolCalls: ToolCall[]; model: string }
