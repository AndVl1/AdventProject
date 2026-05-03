<script setup lang="ts">
import { computed } from 'vue'
import { useChatStore } from '@/stores/chat'

const store = useChatStore()

const selectedModel = computed({
  get: () => store.selectedModel,
  set: (val: string) => {
    store.selectedModel = val
  },
})

const selectedPromptId = computed({
  get: () => store.selectedPromptId,
  set: (val: string) => {
    store.selectedPromptId = val
  },
})
</script>

<template>
  <header class="header">
    <span class="header__title">Assistant</span>

    <select
      v-if="store.availableModels.length > 0"
      v-model="selectedModel"
      class="header__model-select"
      :disabled="store.sending"
      aria-label="Выбор модели"
    >
      <option
        v-for="model in store.availableModels"
        :key="model.id"
        :value="model.id"
      >
        {{ model.label }}
      </option>
    </select>

    <!-- Бэкенд недоступен — модели не загружены -->
    <span v-else class="header__model-unavailable">Model N/A</span>

    <router-link to="/admin/gateway" class="header__link">Gateway Admin →</router-link>

    <select
      v-if="store.availablePrompts.length > 0"
      v-model="selectedPromptId"
      class="header__model-select"
      :disabled="store.sending"
      aria-label="Системный промпт"
    >
      <option
        v-for="preset in store.availablePrompts"
        :key="preset.id"
        :value="preset.id"
      >
        {{ preset.label }}
      </option>
    </select>
  </header>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--color-header-bg);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
  gap: 12px;
}

.header__title {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--color-header-text);
}

.header__model-select {
  padding: 5px 10px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-text);
  font-size: 0.85rem;
  cursor: pointer;
  outline: none;
  transition: border-color 0.15s;
}

.header__model-select:focus {
  border-color: var(--color-accent);
}

.header__model-unavailable {
  font-size: 0.85rem;
  color: var(--color-hint);
}

.header__link {
  font-size: 0.85rem;
  color: var(--color-header-text);
  text-decoration: none;
  margin-left: auto;
}

.header__link:hover {
  color: var(--color-accent);
}
</style>
