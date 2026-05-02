<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'

const store = useChatStore()
const text = ref('')

function handleKeydown(e: KeyboardEvent): void {
  // Enter без Shift — отправить; Shift+Enter — новая строка
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

function submit(): void {
  const trimmed = text.value.trim()
  if (!trimmed || store.sending) return
  store.sendMessage(trimmed)
  text.value = ''
}
</script>

<template>
  <div class="chat-input">
    <textarea
      v-model="text"
      class="chat-input__textarea"
      placeholder="Type a message… (Enter to send, Shift+Enter for newline)"
      rows="1"
      :disabled="store.sending"
      @keydown="handleKeydown"
    />
    <button
      class="chat-input__send"
      type="button"
      :disabled="store.sending || !text.trim()"
      @click="submit"
    >
      <!-- Спиннер во время отправки -->
      <span v-if="store.sending" class="chat-input__spinner" aria-hidden="true" />
      <span v-else>Send</span>
    </button>
  </div>
</template>

<style scoped>
.chat-input {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 10px 16px;
  border-top: 1px solid var(--color-border);
  background: var(--color-bg);
  flex-shrink: 0;
}

.chat-input__textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 9px 12px;
  font-size: 0.95rem;
  font-family: inherit;
  line-height: 1.4;
  background: var(--color-surface);
  color: var(--color-text);
  outline: none;
  transition: border-color 0.15s;
  /* Авто-растягивание через JS не добавляем — rows=1 + overflow достаточно */
  max-height: 160px;
  overflow-y: auto;
}

.chat-input__textarea:focus {
  border-color: var(--color-accent);
}

.chat-input__textarea:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.chat-input__send {
  padding: 9px 18px;
  border: none;
  border-radius: 8px;
  background: var(--color-accent);
  color: var(--color-accent-text);
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 64px;
  height: 40px;
}

.chat-input__send:hover:not(:disabled) {
  background: var(--color-accent-hover);
}

.chat-input__send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Простой CSS-спиннер */
.chat-input__spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid var(--color-accent-text);
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
