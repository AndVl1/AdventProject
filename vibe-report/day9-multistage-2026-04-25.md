# Day 9 — Multi-stage inference (2026-04-25)

## Задача

Расширенная схема извлечения из тикета: `{category, sentiment, severity, affected_component, suggested_action}`. Сравнить два пайплайна:

- **mono** — один LLM-вызов с GBNF на полную схему.
- **multi** — 5 этапов: normalize → category → sentiment → severity → extract+route. На последнем этапе `severity=critical OR category=billing` → принудительно Tier2 (логика дня 8).

Цель — понять, окупается ли разбиение задачи на подзадачи (точность ↑, latency/cost ↑) и на каких моделях.

## Реализация — `ft-lab/scripts/multistage.py`

- GBNF-грамматики на каждый этап: `GBNF_FULL`, `GBNF_NORMALIZE`, `GBNF_ENUM_*`, `GBNF_COMPONENT_ACTION`. Маска logits на этапе сэмплирования — гарантия валидного JSON и enum-значений.
- `Tier(name, base_url, model, no_think, api_key, is_remote)` — переиспользован из дня 8, добавлен флаг `is_remote` для OpenRouter.
- `build_tiers(remote_model=None, tier2_only=False)` — три режима:
  - default — локальные T1 (3B) + T2 (35B-A3B) на 8080/8081.
  - `--remote MODEL` — обе тиры заворачиваются в OpenRouter с одной и той же моделью (тестирование одной remote-модели).
  - `--tier2-only` — обе тиры показывают на локальный T2 (baseline для multi на сильной модели).
- `_call(...)` — унифицированный вызов: локально шлёт `grammar` + `chat_template_kwargs={enable_thinking: False}`, на remote — `response_format={type: json_object}` (GBNF недоступен, грамматика заменяется инструкцией в системном промпте).
- Метрика `affected_component` — Jaccard ≥0.25 по токенам (свободный текст). Остальные поля — exact match по lowercase.

## Тестовый набор — `ft-lab/data/eval/day9_eval.jsonl`

20 примеров, ручная разметка по 5 полям: 10 переиспользовано из `long_cases.jsonl` (день 7), 10 новых — короткие/средние тикеты со всеми категориями и тяжестями. `severity ∈ {low, medium, high, critical}`, `suggested_action ∈ {escalate_eng, reply_template, request_more_info, close}`.

## Ablation (5 моделей × 2 режима)

| модель / режим | mode | all-fields | cat | sent | sev | comp | action | avg lat (ms) | tokens |
|---|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Qwen2.5-3B local (T1) | mono | 10% | 95 | 75 | 60 | 65 | 40 | 580 | 6 610 |
| Qwen2.5-3B local (T1) | multi | 15% | 90 | 70 | 65 | 55 | 50 | 2 554 | 33 213 |
| Qwen3.6-35B-A3B local (T2) | mono | 25% | 95 | 90 | 70 | 55 | 75 | 1 476 | 6 232 |
| **Qwen3.6-35B-A3B local (T2)** | **multi** | **35%** | 85 | 90 | 70 | 60 | 85 | 4 732 | 30 566 |
| GPT-4o-mini (remote) | mono | 10% | 95 | 80 | 55 | 50 | 60 | 929 | 5 745 |
| GPT-4o-mini (remote) | multi | 25% | 90 | 85 | 60 | 55 | 80 | 3 934 | 28 964 |
| MiniMax M2.5 (remote) | mono | 20% | 35 | 30 | 25 | 30 | 30 | 3 147 | 9 120 |
| MiniMax M2.5 (remote) | multi | 0% | 0 | 10 | 0 | 15 | 15 | 14 577 | 41 931 |

Gemini 2.5 Flash и Qwen-remote из таблицы исключены: первая отказала по гео-ограничениям, вторую прервали из-за латентности.

## Анализ

### 1. Multi окупается только на сильной модели

| модель | Δ all-fields (multi − mono) | Δ latency | Δ tokens |
|---|:---:|:---:|:---:|
| Qwen2.5-3B (T1) | +5 п.п. | ×4.4 | ×5.0 |
| Qwen3.6-35B (T2) | **+10 п.п.** | ×3.2 | ×4.9 |
| GPT-4o-mini | +15 п.п. | ×4.2 | ×5.0 |
| MiniMax M2.5 | **−20 п.п.** | ×4.6 | ×4.6 |

На слабой 3B каждая стадия добавляет шум, выигрыш +5 п.п. за ×4 latency — не окупается. На сильной модели декомпозиция режет ошибку (фокус на одном поле = меньше галлюцинаций), trade-off становится пригодным. На MiniMax без GBNF multi разваливается полностью — ни один этап не возвращает структурированный JSON.

### 2. GBNF критичен для слабых моделей

Локальный Qwen2.5-3B с GBNF: cat 95%. MiniMax M2.5 без GBNF (response_format only): cat **35%**. Та же задача, разница в способе констрейнинга. Без grammar модель пишет валидный JSON, но игнорирует enum — пишет "техподдержка" вместо `bug`. На сильных моделях (GPT-4o-mini cat 95% без GBNF) проблема нивелируется качеством инструкции-следования.

**Вывод**: на on-prem 3-7B GBNF обязателен. Remote API без grammar — лотерея, зависит от instruction-tuning поставщика.

### 3. Per-field — самые тяжёлые поля

- **suggested_action** — 40-50% на 3B, 75-85% на сильных. Модель оверфитится на `request_more_info` как safe-fallback. Лечится либо few-shot примерами на каждый класс, либо мерджем близких лейблов (`reply_template + close → auto_resolve`).
- **affected_component** — 50-65% на всех моделях, но метрика Jaccard ≥0.25 строгая. По ручной выборке семантически правильных предсказаний ~80% (`"auth cookie SameSite"` vs gold `"auth/admin login (cookies)"` — Jaccard 0.14, рейтинг false-negative). Решение: понизить порог до 0.15 или ввести LLM-judge.
- **category, sentiment** — 85-95% везде, можно считать решённой задачей.

### 4. Локальный T2 бьёт GPT-4o-mini

Qwen3.6-35B-A3B Q3_K_S multi: **35% all-fields, 4732 ms**. GPT-4o-mini multi: 25%, 3934 ms. На своей задаче (русскоязычные тикеты, GBNF доступен) MoE-модель локально даёт +10 п.п. за сопоставимую latency. Это сильный сигнал: для специализированной NLU-задачи небольшая локальная MoE с грамматикой ≥ proprietary mini-tier API.

### 5. all-fields acc низкий — это математика, не бага модели

Per-field 0.95 × 0.90 × 0.70 × 0.60 × 0.85 ≈ 30%. Метрика «AND по 5 полям» намеренно жёсткая. Реальная бизнес-ценность ближе к среднему per-field (~80% на T2-multi).

### 6. Routing на multi-stage сработал, но мало

В лучшем прогоне (T2 local multi) — 3 эскалации T1→T2 из 100 этапов. То есть на этом эвале правило `severity=critical OR category=billing` срабатывает редко. На большем объёме статистика будет осмысленнее, но пока эффект эскалации в multi на этих 20 примерах исчезающе мал.

## Решения для дня 10

1. **Tier 0 = micro модель/классификатор** перед T1. Кандидаты:
   - TF-IDF + Logistic Regression на тренировочной выборке дня 6 (для category/sentiment).
   - Sentence-embeddings (e5-small) + ANN-индекс, классификация по ближайшему соседу.
   - 0.5-1B LLM (Qwen2.5-0.5B-Instruct, SmolLM2-360M) с GBNF.
2. **Эскалация T0→T1** по тем же сигналам дня 8 (lowprob threshold). Архитектура `Tier`-списка готова, расширение — одна строка.
3. **Жесткие поля** (`suggested_action`, `affected_component`) обработать ансамблем: T0 даёт топ-3 кандидата, T1 выбирает финальный — без полного перегенерирования текста.
4. **Понизить Jaccard threshold** до 0.15 для affected_component на eval — текущая метрика занижает реальное качество.

## Что не покрыто

- **Few-shot для suggested_action** — потенциально +15 п.п. на T1, но требует курации примеров и увеличивает контекст.
- **LLM-judge для affected_component** — корректнее Jaccard, но добавляет ещё один LLM-вызов.
- **Воспроизведение на ≥100 примерах** — 20 примеров шумные, разброс между прогонами 3-5 п.п. Перед днём 10 имеет смысл расширить eval до 50-100.
- **FT Qwen2.5-3B на расширенной схеме** — бонус после дня 10. Гипотеза: после fine-tune T1 догонит T2-mono, multi станет не нужен.

## Артефакты

- `ft-lab/scripts/multistage.py` — пайплайны mono/multi, GBNF-грамматики, флаги `--remote`/`--tier2-only`, авто-генерация tag по имени модели.
- `ft-lab/data/eval/day9_eval.jsonl` — 20 примеров с разметкой по 5 полям.
- `ft-lab/results/day9-{mono,multi}-{tag}.jsonl` — per-row предсказания и эскалации.
- `ft-lab/results/day9-{tag}-report.md` — таблицы по каждой модели (`default`, `tier2`, `4omini`, `mm25-remote`).
- `ft-lab/.env.example` — добавлены `OPENROUTER_API_KEY`, `OPENROUTER_BASE_URL`.

## Вывод одной строкой

Multi-stage окупается только на сильной модели: локальный Qwen3.6-35B-A3B + multi даёт **35% all-fields acc** (+10 п.п. над mono, бьёт GPT-4o-mini на +10 п.п.) ценой ×3 latency. На 3B и без GBNF (remote MiniMax) — разваливается. День 10: Tier 0 перед T1 для дешёвых случаев + расширение eval до 100 примеров.
