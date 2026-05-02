<script setup lang="ts">
// Точка входа приложения — только router-view, вся логика в ChatView
</script>

<template>
  <router-view />
</template>

<style>
/*
 * Глобальные CSS-переменные для тем.
 * Светлая тема — по умолчанию, тёмная — через prefers-color-scheme.
 */
:root {
  --color-bg: #f5f7fa;
  --color-surface: #ffffff;
  --color-surface-alt: #f0f2f5;
  --color-border: #e0e3e8;
  --color-text: #1a1d23;
  --color-hint: #8a93a2;

  --color-accent: #3b82f6;
  --color-accent-hover: #2563eb;
  --color-accent-text: #ffffff;

  --color-header-bg: #1e293b;
  --color-header-text: #f1f5f9;

  --color-error: #dc2626;
  --color-error-bg: #fef2f2;

  --color-warn: #d97706;
  --color-warn-bg: #fffbeb;
}

@media (prefers-color-scheme: dark) {
  :root {
    --color-bg: #0f1117;
    --color-surface: #1a1d23;
    --color-surface-alt: #22262e;
    --color-border: #2d3240;
    --color-text: #e8eaf0;
    --color-hint: #6b7280;

    --color-accent: #3b82f6;
    --color-accent-hover: #60a5fa;
    --color-accent-text: #ffffff;

    --color-header-bg: #0d1117;
    --color-header-text: #e8eaf0;

    --color-error: #f87171;
    --color-error-bg: #1f1215;

    --color-warn: #fbbf24;
    --color-warn-bg: #1c1710;
  }
}

/* Сброс и базовые стили */
*,
*::before,
*::after {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html,
body {
  height: 100%;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: var(--color-bg);
  color: var(--color-text);
  line-height: 1.5;
}

#app {
  height: 100%;
}

button {
  font-family: inherit;
}

textarea {
  font-family: inherit;
}
</style>
