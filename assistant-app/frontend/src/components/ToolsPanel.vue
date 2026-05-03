<script setup lang="ts">
import { useChatStore } from '@/stores/chat'

const store = useChatStore()
</script>

<template>
  <div v-if="store.availableTools.length > 0" class="tools-panel">
    <button
      v-for="tool in store.availableTools"
      :key="tool.id"
      class="tool-chip"
      :class="{ 'tool-chip--active': store.enabledTools.has(tool.id) }"
      :title="tool.description"
      type="button"
      @click="store.toggleTool(tool.id)"
    >
      {{ tool.label }}
    </button>
  </div>
</template>

<style scoped>
.tools-panel {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 16px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.tool-chip {
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-hint);
  font-size: 0.8rem;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
  white-space: nowrap;
}

.tool-chip--active {
  background: var(--color-accent);
  border-color: var(--color-accent);
  color: var(--color-accent-text);
}

.tool-chip:not(.tool-chip--active):hover {
  border-color: var(--color-accent);
  color: var(--color-text);
}
</style>
