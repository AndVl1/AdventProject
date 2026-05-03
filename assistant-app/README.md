# Assistant App

Простой чат-агент с тулами Gmail + WebFetch.

- **Backend:** Spring Boot 3 + Kotlin + Koog 0.7.3
- **Frontend:** Vue 3 + Vite + TypeScript

## Запуск локально

```bash
# зависимости фронта (один раз)
cd frontend && npm install && cd ..

# поднять оба процесса
./scripts/start.sh
```

Backend → `http://localhost:8080`, Frontend → `http://localhost:5173`.

## Переменные окружения

Backend читает из `assistant-app/.env` (или env). Файл НЕ коммитится.

```bash
# минимум одна модель должна быть настроена
OPENROUTER_API_KEY=sk-or-v1-...
ANTHROPIC_API_KEY=sk-ant-...        # опционально

# Gmail OAuth2 (refresh token flow). Без них Gmail-тулы вернут 401.
GMAIL_CLIENT_ID=...
GMAIL_CLIENT_SECRET=...
GMAIL_REFRESH_TOKEN=...
GMAIL_USER_EMAIL=user@gmail.com
```

Шаблон — `.env.example`.

## API

| Endpoint | Что |
|----------|-----|
| `POST /api/chat` | `{message, sessionId?, model, enabledTools[]}` → `{reply, toolCalls[], sessionId}` |
| `GET /api/models` | Список доступных моделей |
| `GET /api/tools` | Список тулов с описаниями |
| `DELETE /api/sessions/{id}` | Очистить историю |

## Тулы

| Тул | Что делает |
|-----|------------|
| `gmail_read` | Читает последние письма (subject, from, snippet) |
| `gmail_send` | Отправляет письмо (to, subject, body) |
| `web_fetch` | GET URL, возвращает текст (HTML strip) |

Все тулы переключаются в UI чекбоксами per-request.
