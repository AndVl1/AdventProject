# Assistant-App: bootstrap + smoke + UI QA

Дата: 2026-05-02
Стэк: Spring Boot 3.3.5 + Kotlin 2.3.10 + Java 21 + Koog 0.7.3 (backend), Vue 3 + Vite + Pinia + axios (frontend), OpenRouter LLM, Gmail API + Jsoup для тулов.

## Что сделано

- **Backend** (`assistant-app/backend/`): Spring Boot + koog-spring-boot-starter, REST API:
  - `GET /api/models` — 4 модели (gpt-4o-mini, claude-haiku-4-5, gemini-2.5-flash, qwen3-235b-a22b)
  - `GET /api/tools` — 3 тула (gmail_read, gmail_send, web_fetch)
  - `POST /api/chat {message, model, enabledTools, sessionId?}` — единый endpoint, `AgentFactory.create` пересобирает агента под каждый запрос с нужным набором тулов и моделью
  - `DELETE /api/sessions/{id}` — сброс истории
  - In-memory `SessionStore`, `ToolCallTracer` через `EventHandlerConfig.onToolCallCompleted/Failed`
  - `application.yml` — порт 8090 (8080 занят cc-proxy)
- **Frontend** (`assistant-app/frontend/`): Vue 3 + Pinia, чат с панелью тулов и селектором модели, `vite.config.ts` с прокси `/api → http://localhost:8090`
- **Deploy**: `scripts/start.sh` подсасывает `.env`, поднимает оба процесса в nohup, ждёт `/api/tools`, пишет PID-файлы; `scripts/stop.sh` глушит обоих

## Грабли, на которые наступил

1. **Port 8080 conflict** — cc-proxy перехватывал. Переехал на 8090, обновил vite proxy.
2. **`@field:Value` на ctor-параметре** не инжектится в Kotlin под Spring 6 — fixed → `@param:Value`. CorsConfig падал с `No qualifying bean of type 'java.lang.String'`.
3. **kotlinx-serialization 1.6.3 vs 1.8.x** — Spring Boot BOM ставит 1.6.3, Koog 0.7.3 был скомпилирован против новых сигнатур → `AbstractMethodError: typeParametersSerializers`. Override в `dependencyManagement` на `1.8.1`.
4. **httpclient5 5.3.x vs 5.5** — Ktor 3.2.2 (apache5 engine, под капотом Koog) дёргает `setHostVerificationPolicy` из 5.5. Override `httpclient5.version=5.5`, заодно `httpcore5.version=5.3.4` (нужен `ProtocolVersionParser`).
5. **Stale `vite.config.js` + `.d.ts`** в репо рядом с `.ts` — Vite предпочёл скомпилированный JS с target 8080, фронт лип к старому порту, API возвращал HTML cc-proxy. Удалил оба, оставил только `.ts`.

## Smoke-тесты (curl)

| Endpoint | Status |
|---|---|
| `GET /api/tools` | 200, 3 тула |
| `GET /api/models` | 200, 4 модели |
| `POST /api/chat` (без тулов, gpt-4o-mini) | 200, "Привет!" |
| `POST /api/chat` (web_fetch, example.com) | 200, "Example Domain..." |
| `POST /api/chat` (qwen-2.5-7b-instruct) | 200, "Привет" |

## UI QA (manual-qa subagent через Chrome MCP)

Все 5 сценариев — PASS. Подробный отчёт: `vibe-report/assistant-app-manual-qa-2026-05-02.md`.

- Главный экран рендерится, селектор моделей и панель тулов видны
- Чат без тулов работает (gpt-4o-mini)
- Web fetch вызывается, tool call карточка показывается в bubble
- Переключение модели на qwen3-235b работает
- 0 console errors, 0 failed network requests

## Что осталось за рамками

- Gmail тулы возвращают заглушку — `GMAIL_REFRESH_TOKEN` не задан в `.env`. Когда юзер прокинет рефреш-токен, тулы заработают (логика готова, основана на `UserCredentials` + `google-api-services-gmail`).
- В списке моделей нет `qwen/qwen-2.5-7b-instruct` — конфигурация ModelsConfig содержит `qwen3-235b-a22b-2507`. Если нужна 7b — добавить в `config/ModelsConfig.kt`.
- Архитектура backend пока не разнесена по слоям (controllers + chat/ + tools/) — Clean слои избыточны для CRUD-обёртки над одним runBlocking. Если будет рост (несколько фич, БД, оркестрация) — рефакторить под domain/data/presentation.
- Антропик-провайдер выключен по умолчанию (`ANTHROPIC_ENABLED=false`), включается env-переменной.

## Команды для запуска

```bash
cd assistant-app
cp .env.example .env  # вписать OPENROUTER_API_KEY, опционально GMAIL_*
./scripts/start.sh    # backend :8090 + frontend :5173
# открыть http://localhost:5173
./scripts/stop.sh     # остановить
```
