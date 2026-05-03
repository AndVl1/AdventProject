<script setup lang="ts">
import { onMounted } from 'vue'
import HeaderBar from '@/components/HeaderBar.vue'
import ToolsPanel from '@/components/ToolsPanel.vue'
import MessageList from '@/components/MessageList.vue'
import ChatInput from '@/components/ChatInput.vue'
import { useChatStore } from '@/stores/chat'

const store = useChatStore()

onMounted(() => {
  store.loadMeta()
})
</script>

<template>
  <div class="chat-view">
    <HeaderBar />
    <ToolsPanel />

    <!-- Баннер "бэкенд недоступен" показывается, если после loadMeta моделей нет -->
    <div
      v-if="store.availableModels.length === 0 && !store.error"
      class="chat-view__banner chat-view__banner--warn"
    >
      Backend unreachable — models not loaded. Check backend at http://localhost:8090.
    </div>

    <!-- Баннер ошибки, закрываемый -->
    <Transition name="banner">
      <div
        v-if="store.error"
        class="chat-view__banner chat-view__banner--error"
      >
        <span>{{ store.error }}</span>
        <button
          class="chat-view__banner-close"
          type="button"
          aria-label="Dismiss"
          @click="store.dismissError()"
        >
          ×
        </button>
      </div>
    </Transition>

    <MessageList />

    <!-- Строка статуса сессии -->
    <div v-if="store.sessionId" class="chat-view__status">
      <span class="chat-view__session">Session: {{ store.shortSessionId }}</span>
      <button
        class="chat-view__clear"
        type="button"
        :disabled="store.sending"
        @click="store.clearSession()"
      >
        Clear chat
      </button>
    </div>

    <ChatInput />
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

/* Баннеры */
.chat-view__banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  font-size: 0.875rem;
  flex-shrink: 0;
}

.chat-view__banner--error {
  background: var(--color-error-bg);
  color: var(--color-error);
  border-bottom: 1px solid var(--color-error);
}

.chat-view__banner--warn {
  background: var(--color-warn-bg);
  color: var(--color-warn);
  border-bottom: 1px solid var(--color-warn);
}

.chat-view__banner-close {
  background: none;
  border: none;
  color: inherit;
  font-size: 1.2rem;
  cursor: pointer;
  padding: 0 4px;
  line-height: 1;
  flex-shrink: 0;
}

/* Строка статуса */
.chat-view__status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 16px;
  border-top: 1px solid var(--color-border);
  background: var(--color-surface);
  flex-shrink: 0;
}

.chat-view__session {
  font-size: 0.78rem;
  color: var(--color-hint);
  font-family: 'Menlo', 'Consolas', monospace;
}

.chat-view__clear {
  font-size: 0.78rem;
  padding: 3px 10px;
  background: none;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  color: var(--color-hint);
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}

.chat-view__clear:hover:not(:disabled) {
  color: var(--color-error);
  border-color: var(--color-error);
  background: none;
}

.chat-view__clear:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Анимация баннера ошибки */
.banner-enter-active,
.banner-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.banner-enter-from,
.banner-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
