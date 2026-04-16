# Bookshelf — bootstrap шаблона full-stack приложения

**Дата**: 2026-04-16
**Профиль**: автономный team-workflow (Phase 1–7, чекпоинты пропущены по явной просьбе — пользователь спит)
**Задача**: подготовить код (без тестов) под следующую сессию. 3 страницы: auth + 2 фичи. Spring + Vue. Собрать, поднять, проверить manual-qa, коммит в main.

## Что сделано

«Книжная полка» (Bookshelf) — персональный трекер книг.

- **Backend** `web-app/backend/`: Spring Boot 3.3.5 + Java 21 + Maven + Spring Security (JWT HS256, jjwt 0.12.6) + Spring Data JPA + H2 in-memory. Пакет `com.andvl.bookshelf`. Maven Wrapper включён.
- **Frontend** `web-app/frontend/`: Vue 3 + TypeScript (strict) + Vite 5 + Pinia + Vue Router 4 + Axios. Composition API, `<script setup>`.
- **Страницы**: `/login`, `/register`, `/books` (список + создание + смена статуса + удаление), `/stats` (счётчики по статусам).

### API
- `POST /api/auth/register|login` → `{ token }`
- `GET /api/books`, `POST /api/books`, `PATCH /api/books/{id}/status`, `DELETE /api/books/{id}`, `GET /api/books/stats` (все требуют JWT)
- Владелец изолирован: `findByIdAndOwnerId` — чужой id всегда 404.
- Единый envelope ошибок `{ error, message }`, коды: VALIDATION_ERROR, UNAUTHORIZED, NOT_FOUND, CONFLICT, INTERNAL_ERROR.
- CORS разрешён для `http://localhost:5173`.

### Frontend
- Pinia stores: `auth` (token/username в localStorage), `books` (list + loading + error).
- Axios interceptors: подстановка `Authorization: Bearer`, auto-logout при 401.
- Router guard на `meta.requiresAuth`, корневой `/` редиректит по авторизации.

## Проверки

- `./mvnw clean package -DskipTests` — PASS (jar 50.5 MB)
- `npm install && npm run build` (vue-tsc + vite) — PASS
- Запуск локально: backend :8080, frontend :5173
- Smoke curl end-to-end: register → JWT → POST book → stats (`{wantToRead:0, reading:1, read:0, total:1}`) — PASS
- manual-qa (Chrome MCP, 10 шагов): регистрация через UI, добавление книги, /stats — **10/10 PASS**, нет ошибок в консоли

## Команды запуска

```bash
# backend
cd web-app/backend && ./mvnw spring-boot:run
# либо: java -jar target/bookshelf-0.0.1-SNAPSHOT.jar

# frontend
cd web-app/frontend && npm install && npm run dev
```

## Известные ограничения (для следующей сессии)

- Тестов нет (ни unit, ни integration) — осознанно, это первый шаг шаблона.
- H2 in-memory — при рестарте данные теряются. Под реальную задачу поменять на Postgres + Flyway.
- JWT секрет захардкожен в `application.yml` (`change-me-change-me-...`). Вынести в env/secret.
- Нет rate limiting, refresh-токенов, logout-на-сервере (stateless by design).
- Нет логирования в файл, нет трейсинга — только дефолт Spring.
- Валидация на фронте минимальная (HTML required). Сообщения об ошибках — сырой текст из ApiError.
- UI без дизайн-системы, только базовые inline-стили.

## Структура коммита

В `main` единым коммитом попадает:
- `web-app/backend/**` (Maven-проект, исходники, pom, wrapper, .gitignore)
- `web-app/frontend/**` (Vite-проект, без `node_modules`, `dist`)
- `vibe-report/bookshelf-*-2026-04-16.md` (отчёты backend / frontend / bootstrap)
- `.work-state/team-state.md`
- обновление `CLAUDE.md` (только уже присутствующее изменение, не связанное с этой задачей — оставляю как есть).

Готово. Проект поднимается двумя командами, базовый flow подтверждён manual-qa.
