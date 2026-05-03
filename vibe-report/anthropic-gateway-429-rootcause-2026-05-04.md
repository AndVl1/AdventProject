# Anthropic Gateway 429 → root cause + фикс

**Дата:** 2026-05-04
**Ветка работы:** feat/day14-secure-exec-loop
**Ветка PR:** fix/anthropic-gateway-transparency

## TL;DR

429 на Opus 4.7 через гейтвей `ANTHROPIC_BASE_URL=http://localhost:8091` — **не лимит ITPM**, а каскад из 4 нарушений прозрачности. Прошлая гипотеза (CC SDK раздувает tools до 111 → 60K tok → ITPM) **отвергнута**: тот же body через референсный Python proxy (`day13_gateway_dashboard.py`) проходит, через Spring — 429. Значит дело в самом гейтвее.

После фикса: `ANTHROPIC_BASE_URL=http://localhost:8091 claude -p "Say only OK"` → `OK` стабильно.

## Корневые причины (4 шт.)

### 1. Инжекция `[GATEWAY NOTICE]` в `system` ломала prompt-cache prefix

Гейтвей-guard при детекте PII в запросе **prepend'ил** в `system[]` блок ~495 символов с инструкцией про placeholder'ы (`REDACTED_<TYPE>_<N>`). Anthropic кэширует **префикс** до `cache_control: {"type":"ephemeral"}` breakpoint. Любая вставка перед существующим cache-блоком → новый hash → cache-miss → весь input (60K+ tok) идёт в ITPM → load-shed 429 без `retry-after` и без `anthropic-ratelimit-*` хедеров.

**Фикс:** убрана инжекция notice. In-place маскировка значений в `walkResult` и так корректно работает (Python proxy так делает и не падает) — модель видит `REDACTED_*` placeholder'ы и без явной инструкции трактует их как opaque.

### 2. `Content-Type: application/json; charset=utf-8` отвергался Anthropic

OkHttp по умолчанию добавлял `; charset=utf-8`. Direct CC и Python proxy шлют ровно `application/json`. С charset Anthropic отдавал 429 / 400.

**Фикс:** `private val jsonMediaType = "application/json".toMediaType()` (без charset suffix).

### 3. `?beta=true` query stripping

Direct CC шлёт `POST /v1/messages?beta=true`. Spring `@PostMapping` отрезал query при формировании upstream URL. Без `?beta=true` Anthropic тоже load-shed'ит.

**Фикс:** `request.queryString` пробрасывается в `AnthropicUpstreamClient.send()` / `sendStream()`, склеивается в URL.

### 4. Gzip ответ не декомпрессировался

Клиент CC шлёт `Accept-Encoding: gzip,br,zstd`. Гейтвей форвардил его в апстрим как часть passthrough headers. OkHttp в этом случае **отключает** свою transparent gzip-декомпрессию (считает: раз клиент сам объявил Accept-Encoding, он сам и распакует). Парсер upstream-ответа получал 3 байта `1f 8b 08` (gzip magic) вместо JSON → "API returned empty or malformed response (HTTP 200)".

**Фикс:** `accept-encoding` добавлен в `DROP_PASSTHROUGH_HEADERS`. OkHttp ставит свой `Accept-Encoding: gzip` и автоматом декодит.

## Что прошло проверку

- Все hop-by-hop / forwarded / auth headers корректно стрипятся.
- User-Agent `claude-cli/2.1.126`, `X-Stainless-*`, `x-app: cli`, `anthropic-dangerous-direct-browser-access: true` — pass-through 1:1.
- `?beta=true` форвардится.
- `anthropic-beta` собирается из multi-value (через `request.getHeaders("anthropic-beta")`).
- Body byte-identity если guard не модифицирует (без Jackson re-serialize) — cache prefix целостен.
- HTTP_1_1 only (HTTP/2 ломает SSE на JDK 21, см. отдельный комментарий в pom.xml).

## Что **НЕ** было причиной

- Не CC SDK режим / 111 vs 10 tools — body размер выдерживается, Python proxy с тем же body не 429ит.
- Не отсутствие `x-anthropic-billing-header` — direct CC его в outbound тоже не шлёт (проверено mitmdump).
- Не отсутствие `x-client-request-id` — гейтвей синтезирует UUID если нет (как делал и раньше).

## Файлы

- `assistant-app/gateway/src/main/kotlin/ru/andvl/gateway/api/AnthropicMessagesController.kt`
  - `DROP_PASSTHROUGH_HEADERS` += `accept-encoding` (фикс #4)
  - `injectAnthropicRedactionNote` больше не вызывается (фикс #1) — оставлен в коде с TODO на удаление
  - `upstreamQuery` извлекается из `request.queryString` и передаётся в client (фикс #3)
- `assistant-app/gateway/src/main/kotlin/ru/andvl/gateway/llm/AnthropicUpstreamClient.kt`
  - `jsonMediaType = "application/json"` без charset (фикс #2)
  - `messagesUrl(base, query)` склеивает `?$query` (фикс #3)
  - `send` / `sendStream` принимают `upstreamQuery: String?`
- `assistant-app/gateway/src/test/kotlin/ru/andvl/gateway/api/AnthropicMessagesControllerTest.kt`
  - Mock-overrides `send`/`sendStream` обновлены под новую сигнатуру
- `assistant-app/gateway/pom.xml`
  - Добавлена зависимость OkHttp 4.12.0 (вытеснил JDK HttpClient — JDK-8334077 spin-loop на разрыве SSE)

## Проверки

- `mvnw test -Dtest=AnthropicMessagesControllerTest` → 16/16 PASS
- `ANTHROPIC_BASE_URL=http://localhost:8091 claude -p "Say only OK"` → `OK`
- Audit DB `gateway.db`: последний запрос `status=OK`, `upstream_response_json` — корректный JSON (а не gzip magic)

## Follow-up

- [ ] Удалить мёртвый `injectAnthropicRedactionNote` + `REDACTION_NOTICE` (или оставить с явным `@Deprecated`)
- [ ] Ротация OAuth токена пользователя: `claude logout && claude login` (токен утёк в `/tmp` в прошлой сессии при `dumpRequestForDiagnostics`, который уже удалён)
- [ ] Эндпоинты `/v1/models` и `/v1/messages/count_tokens` (отложено)
- [ ] Метрика по cache-hit на стороне гейтвея (по `anthropic-cache-*` ответным хедерам), чтобы такие регрессии ловить автоматом
