import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { api } from '@/api/client'
import type { ChatMessage, Model, PromptPreset, Tool } from '@/api/types'

export const useChatStore = defineStore('chat', () => {
  // --- состояние ---
  const sessionId = ref<string | null>(null)
  const messages = ref<ChatMessage[]>([])
  const selectedModel = ref<string>('')
  const enabledTools = ref<Set<string>>(new Set())
  const availableModels = ref<Model[]>([])
  const availableTools = ref<Tool[]>([])
  const availablePrompts = ref<PromptPreset[]>([])
  const selectedPromptId = ref<string>('default')
  const sending = ref(false)
  const error = ref<string | null>(null)

  // --- вычисляемые ---
  const shortSessionId = computed(() =>
    sessionId.value ? sessionId.value.slice(0, 8) : null,
  )

  const hasMessages = computed(() => messages.value.length > 0)

  // --- действия ---

  async function loadMeta(): Promise<void> {
    error.value = null
    try {
      const [modelsRes, toolsRes, promptsRes] = await Promise.all([
        api.getModels(),
        api.getTools(),
        api.getPrompts(),
      ])

      availableModels.value = modelsRes.models
      availableTools.value = toolsRes.tools
      availablePrompts.value = promptsRes.presets

      // Выбираем первую модель по умолчанию
      if (modelsRes.models.length > 0 && !selectedModel.value) {
        selectedModel.value = modelsRes.models[0].id
      }

      // Все инструменты включены по умолчанию
      if (enabledTools.value.size === 0) {
        enabledTools.value = new Set(toolsRes.tools.map((t) => t.id))
      }
    } catch {
      error.value = 'Не удалось загрузить конфигурацию с бэкенда'
    }
  }

  async function sendMessage(text: string): Promise<void> {
    if (!text.trim() || sending.value) return

    sending.value = true
    error.value = null

    // Добавляем сообщение пользователя сразу (оптимистично)
    messages.value.push({ role: 'user', text: text.trim() })

    try {
      const preset = availablePrompts.value.find((p) => p.id === selectedPromptId.value)
      const res = await api.sendMessage({
        message: text.trim(),
        sessionId: sessionId.value ?? undefined,
        model: selectedModel.value,
        enabledTools: Array.from(enabledTools.value),
        systemPrompt: preset?.systemPrompt,
      })

      sessionId.value = res.sessionId

      messages.value.push({
        role: 'assistant',
        text: res.reply,
        toolCalls: res.toolCalls,
        model: res.model,
      })
    } catch {
      error.value = 'Ошибка при отправке сообщения'
      // Убираем оптимистично добавленное сообщение пользователя
      messages.value.pop()
    } finally {
      sending.value = false
    }
  }

  async function clearSession(): Promise<void> {
    if (sessionId.value) {
      try {
        await api.clearSession(sessionId.value)
      } catch {
        // Даже при ошибке сети сбрасываем локальное состояние
      }
    }
    sessionId.value = null
    messages.value = []
    error.value = null
  }

  function toggleTool(id: string): void {
    const next = new Set(enabledTools.value)
    if (next.has(id)) {
      next.delete(id)
    } else {
      next.add(id)
    }
    enabledTools.value = next
  }

  function dismissError(): void {
    error.value = null
  }

  return {
    sessionId,
    shortSessionId,
    messages,
    selectedModel,
    enabledTools,
    availableModels,
    availableTools,
    availablePrompts,
    selectedPromptId,
    sending,
    error,
    hasMessages,
    loadMeta,
    sendMessage,
    clearSession,
    toggleTool,
    dismissError,
  }
})
