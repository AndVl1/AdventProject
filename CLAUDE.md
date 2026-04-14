# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Стэк

Kotlin Multiplatform (Android + iOS + Desktop/JVM) на Compose Multiplatform 1.10. Один Gradle-модуль `composeApp` со всеми таргетами. UI-разделяемый — общий Compose-код в `commonMain`, точки входа в `androidMain` (`MainActivity`), `jvmMain` (`main.kt`), `iosApp` (Swift `iOSApp.swift` + `ContentView.swift`).

Ключевые библиотеки: `androidx.lifecycle.viewmodel.compose` (общий ViewModel из `org.jetbrains.androidx.lifecycle`), Ktor 3 client (`core`, `content-negotiation`, `serialization-kotlinx-json`) с движками per-target — OkHttp/Android, Darwin/iOS, CIO/JVM. Сериализация — `kotlinx.serialization` через плагин `kotlinSerialization`.

Язык общения и комментариев: русский.

## Команды

```bash
./gradlew :composeApp:run                  # Desktop (JVM)
./gradlew :composeApp:assembleDebug        # Android APK (нужен Android SDK в local.properties)
./gradlew :composeApp:compileKotlinJvm     # быстрая проверка common+jvm
./gradlew :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinIosSimulatorArm64  # iOS
./gradlew :composeApp:allTests             # все тесты по таргетам
./gradlew :composeApp:jvmTest --tests "FQCN.method"  # один тест на JVM
```

iOS собирается через Xcode (`iosApp/iosApp.xcodeproj`) или `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`.

## Архитектура

Используем Clean Architecture с тремя слоями. В KMP-проекте слои располагаются **только в `commonMain`** (если нет реальной платформенной специфики).

```
commonMain/kotlin/ru/andvl/advent/advenced/
├── data/            # реализации репозиториев, DTO, источники данных (Ktor, БД, кэш)
│   ├── remote/      # Ktor-клиенты, DTO с @Serializable, маппинги DTO -> domain
│   └── repository/  # *RepositoryImpl, реализуют интерфейсы из domain
├── domain/          # чистый Kotlin: модели, репозиторий-интерфейсы, use cases
│   ├── model/       # доменные модели (без @Serializable, без UI-полей)
│   ├── repository/  # интерфейсы — здесь объявляются контракты
│   └── usecase/     # один публичный operator fun invoke на use case
└── presentation/
    ├── <feature>/
    │   ├── <Feature>Screen.kt        # @Composable, без бизнес-логики
    │   ├── <Feature>ViewModel.kt     # androidx.lifecycle.ViewModel, StateFlow<UiState>
    │   └── <Feature>UiState.kt       # sealed interface: Loading/Success/Error
    └── common/      # переиспользуемые Composable, темы, превью
```

### Правила слоёв

- **domain не зависит ни от чего** (ни Compose, ни Ktor, ни Android). Если в `domain/` появляется импорт `androidx.*` или `io.ktor.*` — это ошибка.
- **data зависит только от domain.** Маппинг DTO → domain делается в `data/`, наружу торчат только доменные модели.
- **presentation зависит от domain** (через use cases или интерфейсы репозиториев), `data` подключается только на уровне DI/композиции графа.
- ViewModel держит `MutableStateFlow<UiState>` и публикует `asStateFlow()`. UI собирает через `collectAsStateWithLifecycle()`. UI-состояние — `sealed interface`, не nullable-поля и не флаги `isLoading/isError` рядом.
- Use case — один публичный метод (`operator fun invoke`), принимает зависимости через конструктор.
- Compose-экран не вызывает Ktor/репозитории напрямую и не делает `LaunchedEffect { httpClient.get(...) }` — это работа ViewModel.

### Платформенный код (expect/actual)

`expect/actual` живёт **только в `data/` или в инфраструктуре** (HTTP-движки, БД, доступ к платформенным API). Текущий пример — `Platform.kt` (commonMain) + `Platform.android.kt` / `Platform.jvm.kt` / `Platform.ios.kt`.

Платформенные зависимости движков Ktor подключаются в соответствующих source set в `composeApp/build.gradle.kts` (`androidMain` → OkHttp, `iosMain` → Darwin, `jvmMain` → CIO). Создание `HttpClient` — через `expect fun httpClient(): HttpClient` в data-слое, а не в ViewModel/Composable.

## Антипаттерны (не делать)

- **Ktor/репозиторий внутри `@Composable` или `LaunchedEffect`.** Сетевые вызовы — только через ViewModel и use case. Текущий `OpenRouterScreen.kt` — временный демонстрационный код, при росте фичи рефакторить в `data/remote/OpenRouterApi` + `domain/usecase/GetTopModelsUseCase` + `presentation/models/ModelsViewModel`.
- **`expect/actual` в `presentation/`.** UI обязан быть полностью общим. Платформенные различия (тема, системные цвета, share-sheet) пробрасываются сверху через параметры/CompositionLocal, фабрика создаётся в платформенной точке входа.
- **DTO в domain или UI.** Классы с `@Serializable` и snake_case полями (`@SerialName("context_length")`) не должны утекать выше `data/`. Маппинг — в `data/remote/<feature>/Mappers.kt`.
- **Импорт `data` из `presentation`.** Presentation знает только про domain-интерфейсы. Подключение реализации — задача DI/корневого графа в платформенной точке входа.
- **God-ViewModel / God-UseCase.** Один use case = одна операция. Если у класса >1 публичного метода с разной семантикой — разделить.
- **Бизнес-логика в `@Composable`.** В Composable допустимы только верстка и подписки на state. Любые `if/when` по доменным правилам — в ViewModel или use case.
- **`runBlocking`, `GlobalScope`, `Dispatchers.Main` хардкодом.** Корутины — только через `viewModelScope`. Диспетчеры передавать через конструктор для тестируемости.
- **Изменяемое состояние наружу.** Из ViewModel наружу — `StateFlow`/`Flow`, не `MutableStateFlow` и не `var`.
- **Отсутствие явного состояния ошибки.** `try/catch` без перевода в `UiState.Error` — нарушение. UI всегда должен иметь возможность отрисовать любую ветку.
- **Раздувание `commonMain` платформенными хаками** (`if (Platform.name == "Android")`). Развилка по платформе = `expect/actual` в data, а не runtime-проверка.
- **Mutable singletons / Service Locator из коробки.** Зависимости пробрасываются через конструкторы. DI-фреймворк (Koin/Metro) добавляется централизованно, не точечно.

## Конвенции

- Пакет всех новых файлов: `ru.andvl.advent.advenced.<layer>.<feature>`.
- Отчёты по задачам — `./vibe-report/<slug>-<YYYY-MM-DD>.md`, e2e-сценарии — `./vibe-report/<slug>-e2e-scenario.md` (см. глобальный профиль).
- Ветки: `feat/<name>`, `fix/<name>`, `refactor/<name>`.
- Перед тем как репортить изменения готовыми, прогнать `./gradlew :composeApp:compileKotlinJvm` (быстро) и, если есть Android SDK, `:composeApp:assembleDebug`.
