# Manual QA Report — assistant-app (перезапуск после фикса)

**Date**: 2026-05-02
**Platform**: Web (Chromium via agent-browser)
**Environment**: http://localhost:5173 → backend http://localhost:8090
**Fix**: удалён `vite.config.js`, активен только `vite.config.ts` с target 8090

---

## Tests Executed

### Test 1: Главный экран
**Status**: PASS

Нет баннера "Backend unreachable". Селектор модели: 4 опции (GPT-4o Mini, Claude Haiku 4.5, Gemini 2.5 Flash, Qwen3 235B). Панель тулов: 3 чекбокса-пилюли (Gmail: Read, Gmail: Send, Web Fetch), все активны по умолчанию.

**Screenshot**: `screenshot-2026-05-02T20-31-28-159Z-hmcnpz.png`

---

### Test 2: Чат без тулов — GPT-4o Mini
**Status**: PASS

1. Отключены все 3 тула кликом по каждому (class `tool-chip` без `--active`)
2. Модель: openai/gpt-4o-mini
3. Сообщение: "скажи 'привет' одним словом"
4. Ответ: "Привет!" — корректно, за ~5 сек
5. Под ответом отображается `openai/gpt-4o-mini`

**Screenshot**: `screenshot-2026-05-02T20-32-34-014Z-tfavpk.png`

---

### Test 3: Чат с web_fetch
**Status**: PASS

1. Включён только Web Fetch (остальные неактивны)
2. Сообщение: "скачай https://example.com и скажи заголовок страницы"
3. Ответ: "Заголовок страницы с сайта https://example.com: "Example Domain"."
4. Карточка tool call `web_fetch` отображается в пузыре ответа

Бэкенд лог подтверждает: `Executing tool (name: web_fetch, args: {"url":"https://example.com"})`

**Screenshot**: `screenshot-2026-05-02T20-33-25-740Z-wgjnjz.png`

---

### Test 4: Переключение модели — Qwen3 235B
**Status**: PASS

Модель `qwen/qwen-2.5-7b-instruct` отсутствует в списке (есть `qwen/qwen3-235b-a22b-2507 → Qwen3 235B`). Протестирована Qwen3 235B.

1. Выбрана Qwen3 235B через select
2. Сообщение: "1+1"
3. Ответ: "1 + 1 = 2." — корректно
4. Карточка ответа содержит `qwen/qwen3-235b-a22b-2507`

**Screenshot**: `screenshot-2026-05-02T20-34-21-353Z-2ne62g.png`

---

### Test 5: Console/Network
**Status**: PASS

Все API вызовы возвращают 200/204:
- `/api/models 200`, `/api/tools 200`, `/api/chat 200` (3×), `/api/sessions/* 204`

Бэкенд лог: ошибок нет. Единственный WARN — `GMAIL_REFRESH_TOKEN не задан — Gmail-инструменты вернут заглушку` (ожидаемо, не конфигурация QA-стенда).

---

## Summary

**Total Tests**: 5
**Passed**: 5
**Failed**: 0

**Issues Found**:
- MINOR: в задании запрашивалась модель `qwen/qwen-2.5-7b-instruct`, которой нет в списке. Доступна только `Qwen3 235B`. Проксирование работает корректно на 8090.

**Recommendation**: READY FOR RELEASE

---

## Screenshots

| Шаг | Файл |
|-----|------|
| Главный экран (fix verified) | `screenshot-2026-05-02T20-31-28-159Z-hmcnpz.png` |
| Чат без тулов — GPT-4o Mini | `screenshot-2026-05-02T20-32-34-014Z-tfavpk.png` |
| Чат с web_fetch | `screenshot-2026-05-02T20-33-25-740Z-wgjnjz.png` |
| Переключение на Qwen3 235B | `screenshot-2026-05-02T20-34-21-353Z-2ne62g.png` |
