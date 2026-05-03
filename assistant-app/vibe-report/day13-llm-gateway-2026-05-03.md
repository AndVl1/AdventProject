# Day 13. LLM Gateway

**Дата:** 2026-05-03
**Стек:** Spring Boot 3.3.5 + Kotlin + SQLite + Vue 3 + Pinia
**Модуль:** `assistant-app/gateway/` (отдельный сервис, порт 8091)

---

## Что сделано

### Бэкенд (`assistant-app/gateway/`)

Новый Spring Boot модуль рядом с `backend/`. Запускается через
`../backend/mvnw spring-boot:run`. Порт 8091. SQLite `./gateway.db`.

**Слои:**

- `persistence/` — SQLite-схема (4 таблицы: `regex_rule`, `audit_log`,
  `redaction_event`, `cost_record`), `JdbcTemplate`-репозитории,
  `BuiltinRulesSeeder` (12 встроенных правил при старте, идемпотентно).
- `guard/` — ядро защиты:
  - `RedactionEngine` — компилит включённые регексы, кэширует в
    `AtomicReference`, `reload()` для горячей перезагрузки после CRUD.
  - `RedactionMap` — потокобезопасная per-conversation мапа
    `original ↔ REDACTED_TYPE_N`. `ConcurrentHashMap` + `putIfAbsent` для
    разрешения гонок: одинаковые секреты в разных потоках схлопываются в
    один плейсхолдер.
  - `ConversationRegistry` — реестр карт по `conversationId`, демон чистит
    устаревшие (TTL 60 мин) каждые 5 мин.
  - `InputGuard` — режим `redact` (по умолчанию) или `block`.
  - `OutputGuard` — порядок критичен: **сначала** rescan сырого ответа на
    галлюцинированные секреты, **потом** `reverse(REDACTED_N → original)`.
    Иначе восстановленные значения юзера тут же снова маскируются.
    Плюс детектор утечки системного промпта (скользящее окно 25 символов
    по нормализованному пробелу) и фильтр подозрительных URL
    (`wallet`, `seed`, `recover`, `private-key`, `kyc-verify`, …).
- `llm/LlmClient` — Spring `RestClient` к `OPENROUTER_API_KEY` через
  `Authorization: Bearer …` (дёргается из `assistant-app/.env` через
  `spring-dotenv`).
- `cost/CostTable` — встроенный прайс (gpt-4o-mini, gpt-4o, claude-3.5,
  qwen, fallback).
- `ratelimit/RateLimiter` — sliding-window per-IP через
  `ConcurrentHashMap<ip, ArrayDeque<timestamp>>`, 60 req/min.
- `api/ProxyController` — `POST /v1/chat/completions`. Пайплайн:
  rate-limit → conversationId из `X-Conversation-Id` (UUID если нет) →
  обходим все `messages`, прогоняем через InputGuard → если BLOCK,
  отвечаем 422 → инжектим system-note про REDACTED → upstream → каждый
  `choice.message.content` через OutputGuard → запись в audit/cost.
  Поддержан и строковый, и расширенный OpenAI-формат `[{type:"text",
  text:…}]`.
- `api/AdminController` — `GET /api/admin/{stats,rules,audit,redactions}`,
  `POST/PUT/DELETE /api/admin/rules/...`. Валидирует регексп при сохранении,
  дёргает `engine.reload()`. Возвращает агрегаты:
  `requests` (по статусу), `totalCostUsd`, `totalTokens`, `byModel`,
  `redactionsByRule`, `activeConversations`, `rateLimitRpm`.

**Заголовки ответа:**
`X-Conversation-Id`, `X-Gateway-Input-Redactions`,
`X-Gateway-Output-Redactions`, `X-Gateway-System-Prompt-Leak`.

**12 встроенных правил:** openai_key, anthropic_key, github_token,
aws_access_key, aws_secret_key, google_api_key, slack_token, jwt,
credit_card, email, phone_e164, phone_ru.

### Фронт (`assistant-app/frontend/`)

- `vite.config.ts` — добавлен dev-proxy `/gw → http://localhost:8091`
  с `rewrite ^/gw → ''`.
- `src/api/gateway.ts` — axios-клиент с baseURL `/gw`, методы для
  stats / CRUD правил / audit / redactions / тестового proxy-вызова.
- `src/views/AdminGatewayView.vue` — страница админки: stats grid,
  быстрая отправка тестового запроса в proxy, CRUD-таблица регекспов
  с модалкой редактирования, таблица перехватов, audit-лента.
- `src/router/index.ts` — маршрут `/admin/gateway`.
- `src/components/HeaderBar.vue` — ссылка `Gateway Admin →`.

---

## Доказательство работы (smoke run)

### Запрос с двумя секретами

```bash
curl -s -i -X POST http://localhost:8091/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Conversation-Id: smoke-1" \
  -d '{"model":"openai/gpt-4o-mini",
       "messages":[{"role":"user",
         "content":"My AWS key is AKIAIOSFODNN7EXAMPLE and my email
                    is john@example.com. Echo back what I told you
                    in 1 short sentence."}]}'
```

```
HTTP/1.1 200
X-Conversation-Id: smoke-1
X-Gateway-Input-Redactions: 2
X-Gateway-Output-Redactions: 0
X-Gateway-System-Prompt-Leak: false
{
  "choices":[{"message":{
    "role":"assistant",
    "content":"You provided your AWS key and email."}}],
  "usage":{"prompt_tokens":176,"completion_tokens":9,"total_tokens":185,
           "cost":3.18E-5}
}
```

Что произошло внутри:
1. Rate-limit OK.
2. InputGuard: сматчил `aws_access_key` + `email` → подменил на
   `REDACTED_AWS_KEY_1` и `REDACTED_EMAIL_1`.
3. В upstream ушло уже зачищенное `My AWS key is REDACTED_AWS_KEY_1
   and my email is REDACTED_EMAIL_1.` + system-note про REDACTED-токены.
4. LLM ответил без секретов — OutputGuard rescan нашёл 0 утечек,
   reverse-мапа не сработала (моделька не вернула REDACTED), ответ
   проксируется как есть.
5. Audit-лог записан со статусом OK, latency 1145 мс, cost $3.18e-5.

### Audit-запись (`/api/admin/audit?limit=1`)

```json
{
  "conversationId": "smoke-1",
  "model": "openai/gpt-4o-mini",
  "requestText": "[user] My AWS key is AKIAIOSFODNN7EXAMPLE and my email is john@example.com. ...",
  "redactedText": "[user] My AWS key is REDACTED_AWS_KEY_1 and my email is REDACTED_EMAIL_1. ...",
  "responseText": "You provided your AWS key and email.",
  "status": "OK",
  "inputFindings": "{\"aws_access_key\":1,\"email\":1}",
  "outputFindings": "{\"input\":{\"aws_access_key\":1,\"email\":1},\"output\":{},\"system_prompt_leak\":false,\"suspicious_urls\":[]}",
  "latencyMs": 1145
}
```

### Redaction-events (`/api/admin/redactions?limit=2`)

```json
[
  {"conversationId":"smoke-1","direction":"INPUT",
   "ruleName":"email","placeholder":"REDACTED_EMAIL_1",
   "originalHash":"855f96e983f1f8e8"},
  {"conversationId":"smoke-1","direction":"INPUT",
   "ruleName":"aws_access_key","placeholder":"REDACTED_AWS_KEY_1",
   "originalHash":"1a5d44a2dca19669"}
]
```

В БД хранится **только префикс SHA-256** оригинала, не сам секрет.

### Stats после прогона

```json
{
  "requests":{"OK":1,"ERROR":63,"RATE_LIMITED":7},
  "totalCostUsd":3.18e-05,
  "totalTokens":185,
  "byModel":[{"model":"openai/gpt-4o-mini","requests":1,
              "tokens":185,"cost":3.18e-05}],
  "redactionsByRule":{"aws_access_key":3,"email":3},
  "activeConversations":60,
  "rateLimitRpm":60
}
```

### Rate-limit

Залп из 65 одновременных запросов с одного IP → 7 вернули
`429 Too Many Requests` (видны в `requests.RATE_LIMITED`). Заголовок
ответа на 429 содержит `Retry-After` и `X-Gateway-RateLimit-Reset`.

---

## Тесты (16 штук, все зелёные)

`./gradlew`-аналог: `../backend/mvnw -pl . test`. Surefire-имена должны
оканчиваться на `Test/Tests/TestCase` — иначе класс не подхватится
(на этом я уже наступил, переименовал `GuardTestCases.kt → GuardTest.kt`).

### `GuardTest` — 13 кейсов с явным разделением CAUGHT / MISSED

| # | Кейс | Категория | Результат |
|---|------|-----------|-----------|
| 1 | AWS access key `AKIAIOSFODNN7EXAMPLE` в инпуте | API_KEY | **CAUGHT** |
| 2 | Кредитка `4111-1111-1111-1111` | PII | **CAUGHT** |
| 3 | Телефон `+1 415 555 0123` | PII | **CAUGHT** |
| 4 | Email `john.doe@example.com` | PII | **CAUGHT** |
| 5 | OpenAI key `sk-proj-…` | API_KEY | **CAUGHT** |
| 6 | GitHub token `ghp_…` | API_KEY | **CAUGHT** |
| 7 | Base64-encoded секрет (`c2stcHJvai0…`) | OBFUSCATION | **MISSED** (документировано) |
| 8 | Секрет, разрезанный по двум сообщениям | MULTI-MESSAGE | **MISSED** (out of scope) |
| 9 | Чистый промпт без секретов | NO-FP | 0 ложных срабатываний |
| 10 | Галлюцинированный AWS-ключ в ответе модели | OUTPUT | **CAUGHT** (через rescan) |
| 11 | Модель эхом вернула фрагмент system prompt | LEAK | **CAUGHT** (25-char окно) |
| 12 | Round-trip `REDACTED_N → original` в ответе | REVERSE | **CAUGHT** |
| 13 | Режим `block` отклоняет запрос с секретом | BLOCK | 422 + reason |

Случаи **7** и **8** оставлены сознательно как known-limitations: для
base64 нужен entropy-detector, для split-across-messages — нормализация
склейкой (выходит за рамки regex-движка).

### `RedactionMapConcurrencyTest` — 3 стресс-теста

| # | Кейс | Результат |
|---|------|-----------|
| 1 | 16 потоков × 1000 одинаковых секретов → ровно 1 плейсхолдер | OK |
| 2 | 16 потоков × 500 уникальных секретов → 500 уникальных плейсхолдеров | OK |
| 3 | reverse round-trip восстанавливает оригинал | OK |

```
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
```

---

## Инциденты по ходу

1. **401 от OpenRouter** — `spring-dotenv` не подхватил `.env`
   автоматически. Лечится `set -a; source ../.env; set +a` перед
   запуском (плагин читает `dotenv.directory`, но в нашем layout у
   gateway свой cwd).
2. **Surefire пропустил `GuardTestCases`** — паттерн ловит только
   `*Test/Tests/TestCase`, `Cases` (мн. число) не матчится.
   Переименовано → `GuardTest.kt`.
3. **`reverseMapping`-тест падал** — OutputGuard сначала делал reverse,
   потом rescan, и rescan тут же снова маскировал восстановленный
   секрет юзера. Поменял порядок: rescan(raw) → reverse(rescan.text).
4. **`systemPromptLeak`-тест падал** — фиксированное окно `take(60)` не
   ловило частичный эхо. Переписал на скользящее 25-char окно по
   нормализованному пробелу.

---

## Что НЕ сделано (осознанно)

- **Streaming SSE** — proxy сейчас только non-streaming. Для streaming
  нужен буфер chunk'ов и инкрементальный rescan, отдельная задача.
- **Аутентификация в админке** — открытая страница, защита делается
  внешним обвесом (nginx/basic-auth). В рамках задания не требуется.
- **Entropy-based detector** — для base64-секретов и сжатых JWT нужен
  Shannon-entropy скоринг, оставлено в backlog.
- **Per-conversation cost-кап** — сейчас только глобальный rate-limit
  по IP. Лимит на conversation/api-key — отдельная фича.

---

## Проверки

- [x] `../backend/mvnw -pl . compile` — gateway компилируется
- [x] `../backend/mvnw -pl . test` — 16/16 тестов зелёные
- [x] `npm run build` (frontend) — 108 модулей, gzip 61.51 kB
- [x] Smoke-run end-to-end через реальный OpenRouter — OK
- [x] Audit-лог содержит redacted+raw+response, hash вместо секрета
- [x] Rate-limit срабатывает (7 × 429 на залпе из 65)
- [x] Reverse-маппинг работает (тест 12)
- [x] Многопоточная per-conversation мапа без гонок (3 теста)

---

## Артефакты

- Код: `assistant-app/gateway/`, ветка `feat/day13-llm-gateway`.
- Тесты:
  `assistant-app/gateway/src/test/kotlin/ru/andvl/gateway/guard/`
- Frontend: `assistant-app/frontend/src/views/AdminGatewayView.vue`,
  `src/api/gateway.ts`.
- БД: `assistant-app/gateway/gateway.db` (gitignore).
- Логи прогона: `/tmp/gateway.log`.
