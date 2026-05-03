<script setup lang="ts">
import ToolCallCard from './ToolCallCard.vue'
import type { ChatMessage } from '@/api/types'

defineProps<{ message: ChatMessage }>()
</script>

<template>
  <div class="bubble-row" :class="`bubble-row--${message.role}`">
    <div class="bubble" :class="`bubble--${message.role}`">
      <p class="bubble__text">{{ message.text }}</p>

      <!-- Инструменты показываем только под сообщениями ассистента -->
      <template v-if="message.role === 'assistant' && message.toolCalls.length > 0">
        <ToolCallCard
          v-for="(tc, i) in message.toolCalls"
          :key="i"
          :toolCall="tc"
        />
      </template>

      <span v-if="message.role === 'assistant'" class="bubble__model">
        {{ message.model }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.bubble-row {
  display: flex;
  padding: 4px 16px;
}

.bubble-row--user {
  justify-content: flex-end;
}

.bubble-row--assistant {
  justify-content: flex-start;
}

.bubble {
  max-width: 75%;
  padding: 10px 14px;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.bubble--user {
  background: var(--color-accent);
  color: var(--color-accent-text);
  border-bottom-right-radius: 4px;
}

.bubble--assistant {
  background: var(--color-surface);
  color: var(--color-text);
  border-bottom-left-radius: 4px;
  border: 1px solid var(--color-border);
}

.bubble__text {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
  font-size: 0.95rem;
}

.bubble__model {
  font-size: 0.7rem;
  color: var(--color-hint);
  align-self: flex-end;
  margin-top: 2px;
}
</style>
