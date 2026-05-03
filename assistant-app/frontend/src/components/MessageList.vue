<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import MessageBubble from './MessageBubble.vue'
import { useChatStore } from '@/stores/chat'

const store = useChatStore()

// Ссылка на контейнер для авто-скролла вниз
const listEl = ref<HTMLElement | null>(null)

function scrollToBottom(): void {
  nextTick(() => {
    if (listEl.value) {
      listEl.value.scrollTop = listEl.value.scrollHeight
    }
  })
}

// Скролл вниз при каждом новом сообщении
watch(
  () => store.messages.length,
  () => scrollToBottom(),
)
</script>

<template>
  <div ref="listEl" class="message-list">
    <!-- Пустое состояние -->
    <div v-if="!store.hasMessages" class="empty-state">
      <div class="empty-state__card">
        <p class="empty-state__hint">Try asking:</p>
        <ul class="empty-state__examples">
          <li>"List my recent emails"</li>
          <li>"What's on Hacker News right now?"</li>
        </ul>
      </div>
    </div>

    <MessageBubble
      v-for="(msg, idx) in store.messages"
      :key="idx"
      :message="msg"
    />

    <!-- Индикатор ожидания ответа -->
    <div v-if="store.sending" class="typing-indicator">
      <span></span><span></span><span></span>
    </div>
  </div>
</template>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* Плавный скролл */
  scroll-behavior: smooth;
}

/* Пустое состояние */
.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
}

.empty-state__card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 12px;
  padding: 24px 32px;
  max-width: 400px;
  text-align: center;
}

.empty-state__hint {
  color: var(--color-hint);
  font-size: 0.9rem;
  margin-bottom: 12px;
}

.empty-state__examples {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.empty-state__examples li {
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 8px 14px;
  font-size: 0.88rem;
  color: var(--color-text);
  cursor: default;
}

/* Анимированный индикатор "печатает" */
.typing-indicator {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 16px;
}

.typing-indicator span {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-hint);
  animation: bounce 1.2s infinite;
}

.typing-indicator span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-indicator span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); }
  30% { transform: translateY(-6px); }
}
</style>
