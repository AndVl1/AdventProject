# Экран деталей AI-модели

Профиль: **Feature**

## План
- Domain: расширен `AiModel` (pricing, modality, maxCompletionTokens), добавлен `ModelPricing`, метод `ModelsRepository.getModelById`, `GetModelDetailsUseCase`.
- Data: расширен `OpenRouterModelDto` (pricing/architecture/top_provider), маппер; `ModelsRepositoryImpl.getModelById` ищет в результате `getModels()`.
- Presentation: `presentation/modeldetails/` — `ModelDetailsUiState` (Loading/Success/NotFound/Error), `ModelDetailsViewModel`, `ModelDetailsScreen`.
- DI/композиция: `ModelsGraph` теперь шарит `repository`, выдаёт оба use case.
- Навигация: state-hoisted в `App.kt` через `rememberSaveable` (список ↔ детали). Без новых либ.

## Открытые вопросы
- OpenRouter REST не предоставляет эндпоинта `/models/{id}` без слеша автор/slug — детали берём из общего `/models`. При росте фичи стоит добавить кэш в репозитории, чтобы детали не дёргали сеть повторно.

## Что сделано
- `domain/model/AiModel.kt`, `domain/model/ModelPricing` — расширение.
- `domain/repository/ModelsRepository.kt` — `getModelById`.
- `domain/usecase/GetModelDetailsUseCase.kt` — новый.
- `data/remote/openrouter/OpenRouterDto.kt` — `pricing`, `architecture`, `top_provider`.
- `data/remote/openrouter/Mappers.kt` — маппинг pricing/modality/maxCompletionTokens.
- `data/repository/ModelsRepositoryImpl.kt` — `getModelById`.
- `di/ModelsGraph.kt` — shared repository + `getModelDetails`.
- `presentation/modeldetails/ModelDetailsUiState.kt`, `ModelDetailsViewModel.kt`, `ModelDetailsScreen.kt` — новый экран.
- `presentation/models/ModelsScreen.kt` — колбэк `onModelClick`, кликабельная карточка.
- `App.kt` — навигация между экранами.

## Проверки
- [x] `./gradlew :composeApp:compileKotlinJvm` — OK
- [x] `./gradlew :composeApp:compileKotlinIosSimulatorArm64` — OK
- [ ] `./gradlew :composeApp:assembleDebug` — не запускал (Android SDK в local.properties)
- [ ] `./gradlew :composeApp:jvmTest` — тесты по ViewModel/мапперу ещё не добавлены (см. ниже)
- [x] UiState — все ветки (Loading/Success/NotFound/Error) имеют отрисовку
- [x] Антипаттерны: нет Ktor/репо в Composable, DTO не утекают выше data

## Что осталось за рамками
- Unit-тесты на `ModelDetailsViewModel` (happy path + error + not found) и `OpenRouterModelDto.toDomain` — требуют мокирования `GetModelDetailsUseCase`/Turbine; вынесено в follow-up.
- Кэш репозитория, чтобы не перезапрашивать список при открытии деталей.
- Проверка на Android-таргете — отсутствует локальный SDK в текущем окружении.
