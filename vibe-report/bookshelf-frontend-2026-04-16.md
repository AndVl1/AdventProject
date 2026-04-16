# Bookshelf Frontend — 2026-04-16

## Что сделано

Реализован Vue 3 SPA «Bookshelf» с нуля в директории `web-app/frontend/`.

## Стек

- Vue 3.5 + `<script setup lang="ts">` везде
- TypeScript strict (~5.5)
- Vite 5 + @vitejs/plugin-vue
- Pinia 2.2 (Composition API stores)
- Vue Router 4.4 (createWebHistory)
- Axios 1.7

## Файлы

```
web-app/frontend/
├── package.json
├── tsconfig.json / tsconfig.node.json
├── vite.config.ts          — alias @, port 5173
├── index.html
├── .gitignore
├── .env.development        — VITE_API_BASE_URL=http://localhost:8080
└── src/
    ├── main.ts             — createApp + pinia + router
    ├── App.vue             — AppHeader + router-view + глобальные стили
    ├── env.d.ts            — ImportMetaEnv
    ├── router/index.ts     — /login, /register (public), /books, /stats (requiresAuth), / redirect
    ├── stores/auth.ts      — token/username из localStorage, watch-синхронизация, login/register/logout
    ├── stores/books.ts     — books[], loading, error, CRUD + fetchStats
    ├── services/http.ts    — axios instance, request interceptor (Bearer), response interceptor (401→logout)
    ├── services/authApi.ts — POST /api/auth/login|register
    ├── services/booksApi.ts — GET/POST/PATCH/DELETE /api/books + /api/books/stats
    ├── types/index.ts      — Book, BookStatus, BookStats, AuthRequest, TokenResponse, ApiError
    ├── views/
    │   ├── LoginView.vue   — форма логина, error ref, ссылка на /register
    │   ├── RegisterView.vue — форма регистрации, error ref, ссылка на /login
    │   ├── BooksView.vue   — fetchAll onMounted, loading/error/BookList
    │   └── StatsView.vue   — fetchStats onMounted, 4 карточки
    └── components/
        ├── AppHeader.vue   — только если isAuthenticated, навигация, logout
        ├── BookForm.vue    — title/author/StatusSelect, booksStore.create, очистка
        ├── BookList.vue    — список книг, StatusSelect (updateStatus), Delete (remove)
        └── StatusSelect.vue — v-model:BookStatus, 3 опции
```

## Решение циклических зависимостей http.ts ↔ stores/auth.ts

Доступ к токену через `getActivePinia().state.value['auth']` внутри интерцептора — без статического импорта стора, что исключает circular dependency.

## Проверки

- [x] `npm install` — 73 пакета, без ошибок
- [x] `npm run build` — vue-tsc + vite build PASS, без предупреждений
- [x] `npm run dev` + `curl http://localhost:5173` → HTTP 200

## E2E (при поднятом backend на :8080)

1. Открыть http://localhost:5173 → редирект на /login
2. Зарегистрироваться → попасть на /books
3. Добавить книгу → появляется в списке
4. Изменить статус через select → PATCH /api/books/{id}/status
5. Удалить книгу → DELETE /api/books/{id}
6. Перейти на /stats → 4 числа из GET /api/books/stats
7. Logout → редирект на /login
