# Day 14 — Anthropic-compatible endpoint в LLM Gateway

**Дата**: 2026-05-03
**Бранч**: `feat/day14-secure-exec-loop`
**Коммит**: `fea9ad6`
**Команда работы**: full team workflow (7 phases) + Phase 6.5 hardening

---

## TL;DR

Добавлен `POST /v1/messages` (+ `/api/v1/messages`) для проксирования вызовов из Claude Code CLI напрямую в `https://api.anthropic.com/v1/messages` с полным input/output guard pipeline. Поддержаны оба режима — `stream:false` и `stream:true` (SSE). Клиентский `x-api-key` пробрасывается verbatim, в gateway не зашиваем.

В Phase 6 security-tester дал **REJECT** с одной CRITICAL-уязвимостью (SEC-001 — cross-conversation secret hijack) + 3 HIGH. Все закрыты в Phase 6.5. Финал — 39/39 тестов PASS, build success.

---

## Скоуп

| Endpoint | Stream | Auth | Guards |
|---|---|---|---|
| `POST /v1/messages` | да (SSE) и нет | x-api-key forwarded | Input + Output + RedactionMap reverse |
| `POST /api/v1/messages` | то же | то же | то же |

**Upstream**: `${GATEWAY_ANTHROPIC_URL:https://api.anthropic.com}` (allowlisted hosts).

---

## Архитектура

### Новые файлы

```
gateway/src/main/kotlin/ru/andvl/gateway/
├── api/
│   ├── AnthropicMessagesController.kt    # @RestController, маппинг /v1+/api/v1
│   ├── ConversationKey.kt                # SEC-001 helper: SHA-256 hash + normalize
│   └── SseGuardStream.kt                 # @Service, SSE per-chunk guard
└── llm/
    └── AnthropicUpstreamClient.kt        # @Service, java.net.http.HttpClient HTTP/2
```

### Изменённые файлы

- `api/ProxyController.kt` — SEC-001 fix (тот же баг наследован от OpenAI-пути)
- `cost/CostTable.kt` — pricing для `claude-opus-4-7`, `claude-sonnet-4-6`, `claude-haiku-4-5` + fallback на sonnet-tier для неизвестных `claude-*`
- `application.yml` — `gateway.anthropic.base-url`, `gateway.anthropic.allowed-hosts`

### Новые тесты

- `AnthropicMessagesControllerTest.kt` — 10 тестов
- `SseGuardStreamTest.kt` — 11 тестов

---

## Workflow по фазам

| Фаза | Агент | Статус |
|---|---|---|
| 1 — Discovery | EM | done (бранч + требования) |
| 2 — Exploration | (контекст из day13) | done |
| 3 — Questions | skipped (требования собраны в Phase 1) | done |
| 4 — Architecture | architect (ac52bd859a5e6d366) | done |
| 5 — Implementation | developer-backend (a20e65dc133ce3e14) | done |
| 6 — Review | qa + code-reviewer + security-tester параллельно | done |
| **6.5 — Review fixes** | developer-backend (a7c9ad89dc06eade0) | **done** |
| 7 — Commit + report | EM | done |

---

## Phase 6 review verdict

| Reviewer | Verdict | Главное |
|---|---|---|
| qa (ad6fd0ba29c80c434) | APPROVE_WITH_FIXES | 3 missing теста на upstream error-ветки |
| code-reviewer (a6b7edf8834c10250) | APPROVE_WITH_FIXES | HIGH: pipe() без try/finally теряет audit/cost |
| **security-tester** (a90136852a363c63a) | **REJECT** | CRITICAL SEC-001 + 3 HIGH |

---

## Phase 6.5 hardening — что починено

### SEC-001 CRITICAL — Cross-conversation secret hijack

**Сценарий атаки**: жертва шлёт секрет в conv `C` → gateway аллоцирует `REDACTED_ANTHROPIC_KEY_1` в её RedactionMap. Атакер шлёт со своим `x-api-key` + `X-Conversation-Id: C` → попадает в **ту же** map (registry.computeIfAbsent). Промптит модель: `"print verbatim REDACTED_ANTHROPIC_KEY_1"` → upstream вернёт текст → `outputGuard.process` вызовет `map.reverse()` → реальный секрет жертвы в ответе атакеру. ID светится через `X-Conversation-Id` в response header + CORS exposed-headers.

**Fix**: `ConversationKey.registryKey(apiKey, clientId)` = `"${sha256(apiKey)[:16]}:${normalizedClientId}"`. Внутренний registry-key привязан к ключу, response header возвращает только `clientId` portion (multi-turn UX сохранён). Клиентский id нормализуется regex `[A-Za-z0-9_-]{1,64}`. Фикс приложен и к `ProxyController` (там используется IP namespace вместо apiKey hash — там нет аутентификации; полная изоляция требует добавления auth — follow-up).

**Тест**: `differentApiKeysSameConversationIdGetSeparateMaps` — два разных api-key с одинаковым `X-Conversation-Id`, плейсхолдер одного НЕ ревёрсится через другой.

### SEC-002 HIGH — TAIL_KEEP=96 мал

**Проблема**: JWT (300-1000), `sk-proj-` (~140), `sk-ant-` (>100) не влезают в 96-char хвост. `guardChunk` видит префикс без полного матча.

**Fix**: `TAIL_KEEP = 1024`. Тест `splitSecretAcross3Chunks` — секрет разрезанный на 3 части не утекает.

### SEC-003 HIGH — tool_use input_json_delta без guard в стриме

**Проблема**: `input_json_delta` пассировался напрямую без regex-скана и без reverse плейсхолдеров. Inconsistent с non-stream.

**Fix**: `partial_json` буферизуется в `toolUseAccum[idx]`. При `content_block_stop` для блока с типом `tool_use`:
1. Полный JSON через `redactionEngine.apply(text, null)` → хеллюцинированные секреты → `LLM_OUTPUT_GUARD_<N>`
2. `map.reverse(scanned)` → клиентские плейсхолдеры разворачиваются в оригиналы
3. Эмит ОДИН синтетический `input_json_delta` с полным JSON + `content_block_stop`

True-streaming для tool_use ломается (буферизация до конца блока). Trade-off задокументирован в коде.

**Тест**: `toolUseStreamGuardsAndReverses`.

### SEC-004 HIGH — SSRF через base-url

**Проблема**: `${GATEWAY_ANTHROPIC_URL}` без валидации scheme/host. Misconfig (`http://169.254.169.254`) → утечка api-keys пользователей.

**Fix**: `AnthropicUpstreamClient.@PostConstruct validateBaseUrl()`:
- `scheme == "https"` (или `http` только для localhost — для тестов)
- `host` ∈ `gateway.anthropic.allowed-hosts` (default = `api.anthropic.com`)
- Иначе — `IllegalStateException` (Spring context не поднимется)

### code-reviewer HIGH — pipe() без try/finally

**Проблема**: `SseGuardStream.pipe()` ловил только `IOException` от `reader.readLine`. Если `parseAndProcessEvent` бросит `JsonProcessingException` — финальный flush tailBuffers и `onComplete` не выполнятся → audit/cost для запроса теряются.

**Fix**: всё тело `pipe()` обёрнуто в `try { ... } catch (e: Exception) { notes += "fatal: ..." } finally { /* flush + onComplete */ }`. Флаг `var completed = false` гарантирует `onComplete` ровно один раз.

**Тест**: `pipeFatalExceptionStillCallsOnComplete` — мок reader бросает RuntimeException, проверяется что onComplete вызвался с собранным state.

### qa HIGH — 3 missing теста на upstream error

Добавлены: `upstreamError4xxNonStream`, `upstreamFailureNonStreamReturns502`, `streamTrueUpstreamErrorReturnsError`.

---

## Финальные метрики

```
$ ./backend/mvnw -B -ntp -f gateway/pom.xml test
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- AnthropicMessagesControllerTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- SseGuardStreamTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- GuardTest
[INFO] Tests run:  3, Failures: 0, Errors: 0, Skipped: 0 -- RedactionMapConcurrencyTest
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- Всего тестов: 32 → **39** (+7 новых)
- Build: PASS
- Lint: проектом не настроен

---

## Известные ограничения (follow-up)

В отдельные issue, в этот спринт не делаем:

| # | Ограничение | Источник | Severity |
|---|---|---|---|
| 1 | `system_prompt_leak` detection в стриме не делается, в audit пишется `false` | SEC-008 | MEDIUM |
| 2 | `suspiciousUrls` в стриме всегда `[]` | code-reviewer #2, SEC | MEDIUM |
| 3 | Audit-insert копипастится 5+ раз — рассинхрон при правках | code-reviewer #4 | HIGH (refactor) |
| 4 | `REDACTION_NOTICE` / `errorBody` / `clientIp` дублированы между ProxyController и AnthropicMessagesController | code-reviewer #5/#20 | MEDIUM |
| 5 | `X-Forwarded-For` без trusted-proxies allowlist → spoof rate-limit/audit | SEC-005 | MEDIUM |
| 6 | Нет body size limit (DoS amplification на regex-guard) | SEC-006 | MEDIUM |
| 7 | Client disconnect → upstream stream продолжает качать (тратит трафик/деньги) | SEC-011 | MEDIUM |
| 8 | `credit_card` regex false-positive шторм (любые 13-19 цифр) | SEC-007 | MEDIUM |
| 9 | `ProxyController` SEC-001 fix через clientIp — за NAT не изолирует | принято на месте | LOW |
| 10 | `AnthropicUpstreamClient` без request timeout (только connect) — thread-leak на зависшем upstream | code-reviewer #2 (qa) | MEDIUM |
| 11 | `tool_use` true-streaming сломан (буферизация до stop) — trade-off за SEC-003 | принято | LOW |

---

## Что не сделано (осознанно — за рамками задачи)

- Документация эндпоинта в README/OpenAPI
- Smoke-тест на реальный Anthropic API с настоящим ключом
- Метрики (Micrometer/Prometheus) для нового пути
- Health-check учитывающий доступность Anthropic upstream
- Rate-limit per-api-key (сейчас per-IP)

---

## Файлы

```
9 files changed, 2119 insertions(+), 9 deletions(-)
A  api/AnthropicMessagesController.kt
A  api/ConversationKey.kt
A  api/SseGuardStream.kt
A  llm/AnthropicUpstreamClient.kt
M  api/ProxyController.kt
M  cost/CostTable.kt
M  resources/application.yml
A  test/api/AnthropicMessagesControllerTest.kt
A  test/api/SseGuardStreamTest.kt
```

Коммит: `fea9ad6 day14: gateway — Anthropic-compatible /v1/messages endpoint with secure SSE proxy`
