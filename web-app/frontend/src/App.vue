<script setup lang="ts">
import AppHeader from '@/components/AppHeader.vue'
</script>

<template>
  <AppHeader />
  <main>
    <router-view />
  </main>
</template>

<style>
*,
*::before,
*::after {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #f5f5f5;
  color: #333;
  line-height: 1.5;
}

/* Header */
.app-header {
  background: #2c3e50;
  color: #fff;
  padding: 12px 24px;
  display: flex;
  align-items: center;
  gap: 24px;
}

.header-brand {
  font-size: 1.2rem;
  font-weight: 700;
}

.header-nav {
  display: flex;
  gap: 16px;
  flex: 1;
}

.header-nav a {
  color: #cdd6e0;
  text-decoration: none;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background 0.2s;
}

.header-nav a.router-link-active,
.header-nav a:hover {
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
}

.header-user {
  display: flex;
  align-items: center;
  gap: 12px;
}

.username {
  font-size: 0.9rem;
  color: #cdd6e0;
}

/* Main */
main {
  max-width: 800px;
  margin: 0 auto;
  padding: 24px 16px;
}

.page h1 {
  margin-bottom: 24px;
  font-size: 1.6rem;
  color: #2c3e50;
}

/* Auth */
.auth-page {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: calc(100vh - 60px);
  padding: 16px;
}

.auth-card {
  background: #fff;
  border-radius: 8px;
  padding: 32px;
  width: 100%;
  max-width: 400px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.auth-card h2 {
  margin-bottom: 24px;
  font-size: 1.4rem;
  color: #2c3e50;
}

.auth-link {
  margin-top: 16px;
  font-size: 0.9rem;
  color: #666;
  text-align: center;
}

.auth-link a {
  color: #3498db;
}

/* Forms */
.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  margin-bottom: 4px;
  font-size: 0.9rem;
  color: #555;
}

input[type='text'],
input[type='password'],
select {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 1rem;
  outline: none;
  transition: border-color 0.2s;
}

input[type='text']:focus,
input[type='password']:focus,
select:focus {
  border-color: #3498db;
}

button {
  cursor: pointer;
  padding: 8px 16px;
  border: none;
  border-radius: 6px;
  font-size: 0.9rem;
  background: #3498db;
  color: #fff;
  transition: background 0.2s;
}

button:hover:not(:disabled) {
  background: #2980b9;
}

button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* App Header button */
.app-header button {
  background: rgba(255, 255, 255, 0.15);
  padding: 6px 12px;
}

.app-header button:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.25);
}

/* Error / loading */
.error-text {
  color: #e74c3c;
  font-size: 0.9rem;
  margin-bottom: 12px;
}

.loading-text {
  color: #999;
  padding: 16px 0;
}

.empty-text {
  color: #999;
  padding: 16px 0;
}

/* Book form */
.book-form {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 24px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.book-form h3 {
  margin-bottom: 16px;
  font-size: 1.1rem;
  color: #2c3e50;
}

/* Book list */
.book-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.book-item {
  background: #fff;
  border-radius: 8px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.book-info {
  flex: 1;
  min-width: 0;
}

.book-title {
  display: block;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.book-author {
  display: block;
  font-size: 0.85rem;
  color: #888;
}

.book-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.book-actions select {
  width: auto;
  padding: 6px 10px;
  font-size: 0.85rem;
}

.btn-delete {
  background: #e74c3c;
  padding: 6px 12px;
  font-size: 0.85rem;
}

.btn-delete:hover:not(:disabled) {
  background: #c0392b;
}

/* Stats */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 16px;
}

.stat-card {
  background: #fff;
  border-radius: 8px;
  padding: 24px 16px;
  text-align: center;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.stat-card--total {
  background: #2c3e50;
  color: #fff;
}

.stat-value {
  font-size: 2.5rem;
  font-weight: 700;
  line-height: 1;
  margin-bottom: 8px;
  color: #3498db;
}

.stat-card--total .stat-value {
  color: #fff;
}

.stat-label {
  font-size: 0.85rem;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.stat-card--total .stat-label {
  color: #cdd6e0;
}
</style>
