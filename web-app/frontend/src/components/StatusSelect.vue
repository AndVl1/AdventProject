<script setup lang="ts">
import type { BookStatus } from '@/types'

defineProps<{
  modelValue: BookStatus
}>()

const emit = defineEmits<{
  'update:modelValue': [value: BookStatus]
}>()

function onChange(event: Event) {
  const target = event.target as HTMLSelectElement
  emit('update:modelValue', target.value as BookStatus)
}
</script>

<template>
  <select :value="modelValue" @change="onChange">
    <option value="WANT_TO_READ">Want to read</option>
    <option value="READING">Reading</option>
    <option value="READ">Read</option>
  </select>
</template>
