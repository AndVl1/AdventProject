# Day 11 — Chat Stand (Prompt Injection тестовый стенд)

**Дата:** 2026-05-02  
**Профиль:** Feature  
**Ветка:** feat/day10-tier0

---

## План

### Domain
- `domain/model/chat/ChatMessage.kt` — доменная модель `ChatMessage` + enum `ChatRole`
- `domain/repository/ChatRepository.kt` — интерфейс с `sendChat`
- `domain/usecase/SendChatMessageUseCase.kt` — один `operator fun invoke`

### Data
- `data/remote/openrouter/ChatDto.kt` — `ChatRequestDto`, `ChatMessageDto`, `ChatResponseDto`, `ChoiceDto` с `@Serializable`
- `data/remote/openrouter/ChatMappers.kt` — `ChatMessage ↔ ChatMessageDto`
- `data/remote/openrouter/OpenRouterChatApi.kt` — POST `/api/v1/chat/completions`, ошибки → `ChatApiException`
- `data/repository/ChatRepositoryImpl.kt` — реализует `ChatRepository`
- Расширен `HttpClientFactory.kt` — добавлена `createHttpClientWithAuth(apiKey)` с `defaultRequest` для Bearer-токена

### Presentation
- `presentation/chat/ChatUiState.kt` — sealed interface `Idle | Active(systemPrompt, model, history, isSending, error)`; константы `AVAILABLE_MODELS`, `DEFAULT_SYSTEM_PROMPT`
- `presentation/chat/ChatViewModel.kt` — ViewModel с `viewModelScope`, методы: `onSystemPromptChange`, `onModelChange`, `onUserMessageSend`, `onReset`
- `presentation/chat/ChatScreen.kt` — Composable, только UI + `collectAsStateWithLifecycle()`

### DI / Навигация
- `di/ChatGraph.kt` — lazy-объект, собирает `OpenRouterChatApi → ChatRepositoryImpl → SendChatMessageUseCase → ChatViewModel`
- `App.kt` — расширен sealed `Screen` (ModelList / ModelDetails / Chat), добавлен `Screen.Chat`
- `presentation/models/ModelsScreen.kt` — добавлен параметр `onChatClick` и кнопка "Chat Stand"

---

## Что сделано

### domain/
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/domain/model/chat/ChatMessage.kt` (создан)
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/domain/repository/ChatRepository.kt` (создан)
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/domain/usecase/SendChatMessageUseCase.kt` (создан)

### data/
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/data/remote/openrouter/ChatDto.kt` (создан)
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/data/remote/openrouter/ChatMappers.kt` (создан)
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/data/remote/openrouter/OpenRouterChatApi.kt` (создан)
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/data/repository/ChatRepositoryImpl.kt` (создан)
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/data/remote/HttpClientFactory.kt` (изменён — добавлена `createHttpClientWithAuth`)

### presentation/
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/presentation/chat/ChatUiState.kt` (создан)
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/presentation/chat/ChatViewModel.kt` (создан)
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/presentation/chat/ChatScreen.kt` (создан)

### DI / навигация
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/di/ChatGraph.kt` (создан)
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/App.kt` (изменён)
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/presentation/models/ModelsScreen.kt` (изменён)

### Тесты
- `composeApp/src/commonTest/kotlin/.../openrouter/ChatMappersTest.kt` (создан, 8 тестов)
- `composeApp/src/jvmTest/kotlin/.../presentation/chat/ChatViewModelTest.kt` (создан, 6 тестов)
- `composeApp/build.gradle.kts` (изменён — добавлена зависимость `kotlinx-coroutines-test` в jvmTest)

---

## Проверки

- [x] `./gradlew :composeApp:compileKotlinJvm` — BUILD SUCCESSFUL, без предупреждений
- [x] `./gradlew :composeApp:compileKotlinIosSimulatorArm64` — BUILD SUCCESSFUL
- [x] `./gradlew :composeApp:jvmTest` — BUILD SUCCESSFUL, 14 тестов (8 ChatMappers + 6 ChatViewModel), failures=0, errors=0
- [ ] `./gradlew :composeApp:assembleDebug` — пропущен (Android SDK не настроен в local.properties)

## Что осталось за рамками

- **API-ключ OpenRouter** — вынесен в `local.properties` (см. update ниже). Постоянное хранение токена через DataStore/Keychain вынесено в follow-up.
- **Персистентность истории** — история живёт только в памяти ViewModel. При перезапуске приложения сбрасывается.
- **Stream-режим (SSE)** — не реализован, только non-streaming POST.
- **Markdown-рендеринг ответов** — ответы выводятся plain text.

---

## Update (2026-04-27): вынос ключа в local.properties

**Профиль:** Refactor  
**Цель:** убрать риск утечки `OPENROUTER_API_KEY` в git.

### Что сделано

**Подход:** Вариант A — Gradle-таска генерирует `BuildSecrets.kt` в `build/generated/` (в `.gitignore`).

**Изменённые файлы:**

- `composeApp/build.gradle.kts` — добавлена `abstract class GenerateBuildSecretsTask` с `@Input`/`@OutputDirectory` (совместимо с configuration cache Gradle 8.14), таска `generateBuildSecrets` читает `local.properties` на configuration-time и записывает `BuildSecrets.kt`; `commonMain` подключает `srcDir(generatedSecretsDir)`; все `KotlinCompile`-задачи зависят от `generateBuildSecrets`.
- `composeApp/src/commonMain/kotlin/ru/andvl/advent/advenced/di/ChatGraph.kt` — убрана `private const val OPENROUTER_API_KEY = ""`, добавлен импорт `ru.andvl.advent.advenced.secrets.OPENROUTER_API_KEY`.
- `local.properties.example` — создан, содержит шаблон с `openrouter.api.key=`.

**Генерируемый файл** (не коммитится — в `build/`):

```
composeApp/build/generated/secrets/commonMain/kotlin/
  ru/andvl/advent/advenced/secrets/BuildSecrets.kt
```

### Как использовать

Добавьте в `local.properties` (корень проекта) строку:

```properties
openrouter.api.key=sk-or-v1-ваш_ключ_сюда
```

Получить бесплатный ключ: https://openrouter.ai/keys

### Проверки

- [x] `./gradlew :composeApp:compileKotlinJvm` — BUILD SUCCESSFUL (без ключа)
- [x] `./gradlew :composeApp:compileKotlinIosSimulatorArm64` — BUILD SUCCESSFUL
- [x] `./gradlew :composeApp:jvmTest` — BUILD SUCCESSFUL, все тесты зелёные
- [x] `grep -rn "OPENROUTER_API_KEY\|sk-or" composeApp/src` — значения ключа нет, только импорт и использование константы
- [x] `find composeApp/build -name "BuildSecrets.kt"` — файл появляется после компиляции
- [x] `build/` в `.gitignore` — сгенерированный файл не коммитится

---

## Замечания по архитектуре

Проект использует упрощённый single-module подход (не api/impl разделение). ViewModel берётся через `viewModel { ... }` с ручным созданием из DI-объекта, что соответствует текущим конвенциям (`ModelsGraph`, `ModelsScreen`). Navgation реализована через простой `sealed class Screen` + `remember` (не `rememberSaveable`, т.к. кастомный sealed class требует явного Saver).

---

## Update 2: QA на устройстве (2026-05-02)

Прогнал E2E-сценарии через `claude-in-mobile` CLI на реальном устройстве A063 (Android 15).

### Результаты по сценариям

| Сценарий | Статус | Скриншот |
|---|---|---|
| Запуск приложения → Models экран | ✅ | `/tmp/03_relaunch.jpg` |
| Tap «Chat Stand» → Chat экран | ✅ | — |
| Дефолтный System prompt отображается | ✅ | `/tmp/01_launch.jpg` |
| Дропдаун модели работает | ✅ | `/tmp/07_minimax.jpg` |
| Ввод сообщения + Send | ✅ | `/tmp/06_response.jpg` |
| Loading-индикатор во время запроса | ✅ | `/tmp/07_minimax.jpg` |
| Реальный ответ от модели | ✅ | `/tmp/08_done.jpg` |
| Кнопка «Сброс» очищает историю | ✅ | `/tmp/02_reset.jpg` |
| Error state показывается, не падает | ✅ | `/tmp/06_response.jpg` |

### Найденные баги (исправлены по ходу)

**1. AVAILABLE_MODELS содержал нерабочие модели**
- `meta-llama/llama-3.2-3b-instruct:free` — постоянно rate-limited (429 от провайдера Venice)
- `mistralai/mistral-7b-instruct:free` — больше нет на OpenRouter (404)
- **Фикс:** заменил на `minimax/minimax-m2.5:free`, `qwen/qwen3-next-80b-a3b-instruct:free`, `nvidia/nemotron-3-nano-30b-a3b:free`, `liquid/lfm-2.5-1.2b-instruct:free`. Файл: `presentation/chat/ChatUiState.kt`.

**2. Ошибки от OpenRouter маскировались как «Пустой ответ от модели»**
- OpenRouter на ошибки (401/404/429) возвращает HTTP 200 с телом `{"error":{"code":..., "message":...}}`. Старый `ChatResponseDto` не имел поля `error`, поэтому `choices=emptyList()` → `IllegalStateException("Пустой ответ от модели")`. Пользователь видел misleading сообщение.
- **Фикс:** добавил `ChatErrorDto(code, message)` + поле `error: ChatErrorDto?` в `ChatResponseDto`. В `ChatRepositoryImpl` — проверка `response.error` ПЕРЕД проверкой choices, с пробросом осмысленного сообщения вида `"OpenRouter ошибка (429): ..."`. Файлы: `data/remote/openrouter/ChatDto.kt`, `data/repository/ChatRepositoryImpl.kt`.

### Что НЕ проверял (вне scope QA)

- Длинная multi-turn история (>3 сообщений) — отложено
- Свайп/прокрутка ленты при переполнении — отложено
- Реальные prompt injection атаки — это уже сама задача дня 11

### Готово к day11

Стенд функционален: можно открывать Chat Stand, менять system prompt в реальном времени, выбирать одну из 4 рабочих free-моделей, гонять сценарии role-play / instruction override / extraction.

### ⚠️ Безопасность

Ключ OpenRouter был отправлен пользователем в чат-сессию с Claude — попал в transcript. После завершения дня 11 — **revoke ключ и сгенерировать новый** на https://openrouter.ai/keys.

---

## Update 3: переход на дешёвые платные модели (2026-05-02)

### Проблема free-моделей

Прогон 5 сообщений подряд на `qwen3-next-80b-a3b-instruct:free` → 429 на 4-м: 
> **«Ошибка: OpenRouter ошибка (429): Provider returned error»**

(скрин `/tmp/09_burst.jpg`). Сам error message — корректный, новый формат через `ChatErrorDto`. Но free-модели физически не дают провести длинную сессию.

### Решение: дешёвые платные

`AVAILABLE_MODELS` обновлён (`presentation/chat/ChatUiState.kt`):

| Модель | Цена $/1M | Зачем |
|---|---|---|
| `qwen/qwen-2.5-7b-instruct` | $0.07 | Основной — хорошо следует инструкциям |
| `qwen/qwen3-235b-a22b-2507` | $0.085 | Большой Qwen для сложных промптов |
| `mistralai/mistral-nemo` | $0.025 | Самый дешёвый осмысленный |
| `google/gemma-3-12b-it` | $0.085 | Альтернативный класс моделей |
| `minimax/minimax-m2.5:free` | 0 | Free fallback (rate-limited) |

### Проверка устойчивости

6 сообщений подряд на `qwen-2.5-7b-instruct` → ни одного 429, ответы по теме кредитов на русском (скрин `/tmp/10_qwen_burst.jpg`, `/tmp/11_history.jpg`).

Стоимость одной prompt-injection сессии (≈30 сообщений по 200 токенов вход + 500 выход):
- `qwen-2.5-7b`: ~$0.012 (≈1 копейка)
- `mistral-nemo`: ~$0.005

Бюджет дня 11 — мизерный.

### Что менять для другой модели

Просто тапнуть в дропдаун в UI — ViewModel переключит без перезапуска. Все 5 моделей в одном списке, история чата сохраняется при смене модели.
