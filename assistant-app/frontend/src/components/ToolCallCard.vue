<script setup lang="ts">
import { ref, computed } from 'vue'
import type { ToolCall } from '@/api/types'

const props = defineProps<{ toolCall: ToolCall }>()

const expanded = ref(false)

const TRUNCATE_LIMIT = 300

const resultTruncated = computed(
  () => props.toolCall.result.length > TRUNCATE_LIMIT,
)

const displayResult = computed(() => {
  if (!resultTruncated.value || expanded.value) return props.toolCall.result
  return props.toolCall.result.slice(0, TRUNCATE_LIMIT) + '…'
})
</script>

<template>
  <div class="tool-card" :class="{ 'tool-card--error': !toolCall.ok }">
    <div class="tool-card__header" @click="expanded = !expanded">
      <span class="tool-card__icon">{{ toolCall.ok ? '🔧' : '⚠️' }}</span>
      <span class="tool-card__name">{{ toolCall.name }}</span>
      <span class="tool-card__toggle">{{ expanded ? '▲' : '▼' }}</span>
    </div>

    <div v-if="expanded" class="tool-card__body">
      <p class="tool-card__label">Arguments</p>
      <pre class="tool-card__pre">{{ JSON.stringify(toolCall.args, null, 2) }}</pre>

      <p class="tool-card__label">Result</p>
      <pre class="tool-card__pre tool-card__pre--result">{{ displayResult }}</pre>

      <button
        v-if="resultTruncated"
        class="tool-card__show-more"
        type="button"
        @click.stop="expanded = !expanded"
      >
        <!-- expanded здесь управляется кнопкой на заголовке, кнопка Show more просто разворачивает -->
      </button>
    </div>

    <!-- Кнопка показа полного результата при свёрнутом состоянии (если было обрезано) -->
    <div
      v-if="!expanded && resultTruncated"
      class="tool-card__preview"
      @click="expanded = true"
    >
      <pre class="tool-card__pre tool-card__pre--result">{{
        toolCall.result.slice(0, TRUNCATE_LIMIT) + '…'
      }}</pre>
      <button class="tool-card__show-more" type="button">Show more</button>
    </div>
  </div>
</template>

<style scoped>
.tool-card {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  overflow: hidden;
  margin-top: 6px;
  font-size: 0.82rem;
}

.tool-card--error {
  border-color: var(--color-error);
}

.tool-card__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--color-surface-alt);
  cursor: pointer;
  user-select: none;
}

.tool-card__name {
  font-weight: 600;
  flex: 1;
  color: var(--color-text);
}

.tool-card__icon {
  font-size: 0.9rem;
}

.tool-card__toggle {
  color: var(--color-hint);
  font-size: 0.7rem;
}

.tool-card__body {
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  background: var(--color-bg);
}

.tool-card__preview {
  padding: 8px 10px;
  background: var(--color-bg);
  cursor: pointer;
}

.tool-card__label {
  font-size: 0.75rem;
  color: var(--color-hint);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 2px;
}

.tool-card__pre {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 4px;
  padding: 6px 8px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text);
  font-family: 'Menlo', 'Consolas', monospace;
  font-size: 0.78rem;
  line-height: 1.45;
  max-height: 300px;
  overflow-y: auto;
}

.tool-card__pre--result {
  color: var(--color-hint);
}

.tool-card--error .tool-card__pre--result {
  color: var(--color-error);
}

.tool-card__show-more {
  align-self: flex-start;
  background: none;
  border: none;
  color: var(--color-accent);
  font-size: 0.8rem;
  cursor: pointer;
  padding: 2px 0;
  text-decoration: underline;
}
</style>
