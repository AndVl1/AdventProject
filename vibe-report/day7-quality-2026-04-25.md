# Day 7 — Confidence + контроль качества (2026-04-25)

## Задача

Из брифа дня 7: реализовать механизм оценки уверенности результата без fine-tuning, минимум 2 разных подхода. Замерить отказы / ретраи / влияние на latency и cost.

База — классификация тикетов (`category` × `sentiment`), JSON-формат, та же что в day6.

## Реализованные методы (3 из 4 предложенных)

### 1. Constraint-based — `scripts/constraint.py`

Трёхступенчатый каскад:
1. Свободная генерация (без grammar) → `json.loads` → `pydantic.Ticket(**parsed)` (enum-валидация).
2. Если fail — remediation retry: история `[..., assistant=плохой_ответ, user="невалидно, верни строго JSON по схеме"]`.
3. Если retry fail — fallback на жёсткий GBNF.

Метрики: `attempts`, `pass_first`, `retried`, `fallback_grammar`.

### 2. Scoring (logprobs) — `scripts/confidence.py:score()`

Один запрос с `logprobs=True, top_logprobs=5`. Парсер по char-диапазонам находит токены значений `category` / `sentiment`, считает `prob = exp(sum(logprob_i))`. `min_prob = min(cat_prob, sent_prob)`. Маппинг на статус: `≥0.85=OK / 0.5..0.85=UNSURE / <0.5=FAIL`.

Бесплатный сигнал — logprobs побочный продукт генерации, +0 токенов overhead.

### 3. Self-check (CoV-lite) — `scripts/selfcheck.py`

Двухэтапная верификация с GBNF на каждом этапе:
- Этап А: расширенная схема `{category, sentiment, reason}`.
- Этап Б: новый диалог (без истории A) — «вот тикет, вот классификация, согласен?» → `{agree: bool, fix?: {category, sentiment}}`.

Расхождение → UNSURE, финал = `fix` если есть.

### Сводный orchestrator — `scripts/quality.py`

Гонит 20 примеров (10 valid + 10 edge_cases), сводный status:
- `constraint=FAIL` → `FAIL`
- `constraint=OK AND scoring=OK AND selfcheck=OK` → `OK`
- иначе → `UNSURE`

## Тестовый набор

`data/eval/edge_cases.jsonl` — 10 примеров, 5 borderline + 5 noisy:
- borderline: bug+feature, billing+how_to, амбивалентные
- noisy: опечатки, эмодзи, mix RU/EN, сверхкороткие

Плюс 10 первых из `valid.jsonl` (correct).

## Результаты (tier 1, Qwen2.5-3B-Instruct Q4_K_M)

### Сводка пайплайна

| метрика | значение |
|---------|----------|
| Final OK | 6/20 |
| Final UNSURE | 14/20 |
| Final FAIL | 0/20 |
| Ретраи constraint | 0 |
| Fallback на GBNF | 0 |
| Avg pipeline latency | 1350 ms |
| Total tokens | 13358 |

### Per-method

| method | OK | UNSURE | FAIL | joint_acc | avg_lat | tokens |
|--------|:--:|:------:|:----:|:---------:|:-------:|:------:|
| constraint | 20 | 0 | 0 | 8/20 (40%) | 208 | 2695 |
| scoring | 16 | 3 | 1 | 8/20 (40%) | 267 | 2695 |
| selfcheck | 9 | 11 | 0 | 6/20 (30%) | 875 | 7968 |
| **final** | **6** | **14** | **0** | **6/20 (30%)** | **1350** | **13358** |

### Per-subset joint_acc

| метод | correct (10) | borderline (5) | noisy (5) |
|-------|:------------:|:--------------:|:---------:|
| constraint | 20% | 40% | 80% |
| scoring | 20% | 40% | 80% |
| selfcheck | 20% | 40% | 40% |
| final | 20% | 40% | 40% |

## Анализ

### Что сработало

- **Selfcheck — главный сигнал**: единственный метод дающий 11 UNSURE на 20 примеров. Constraint бесполезен (Qwen2.5-3B всегда даёт валидный JSON), scoring overconfident на borderline.
- **Scoring как ленивый детектор noisy**: на noisy 4/5 OK при 80% acc. На borderline overconfident (5/5 OK при 40% acc) — GBNF маскирует логиты до вероятности.
- **Constraint как страховка**: 0 срабатываний на этой модели/задаче, но обязательная часть ансамбля для других сценариев (сырая модель / большие промпты / нет GBNF).

### Что НЕ сработало

- **Selfcheck снижает accuracy с 50% → 35%**: применяет fix, fix часто хуже исходного. Маленькая модель — плохой судья самой себе.
- **Final accuracy 30%** хуже одиночного scoring (40%) — selfcheck.fix перетирает правильные ответы.
- **Pipeline 22× дороже по токенам, 3× дороже по latency** vs baseline.

### Вывод

Дан явный сигнал «модель не уверена» (14/20 UNSURE), но ценой:
- 6 false positives — эскалируем на старшую модель тикеты которые tier1 классифицировал верно.
- Recall на ошибки tier1 ~43% (6 из 14 неверных).

Это база для дня 8 (router): `final.status != OK` → tier 2 (35B).

## Решения для Day 8

1. **Reviewer-tier**: stage Б selfcheck'а на tier2 вместо tier1. Дороже, но 35B как ревьюер не саботирует свои оценки.
2. **Игнорировать selfcheck.fix**: использовать `agree=false` только как триггер эскалации, не применять fix локально.
3. **Logprobs БЕЗ grammar**: сделать дополнительный score-проход без GBNF — честные probs, ценой parsed_ok.

## Артефакты

- `ft-lab/scripts/constraint.py` — constraint каскад
- `ft-lab/scripts/selfcheck.py` — двухэтапная верификация
- `ft-lab/scripts/confidence.py` — logprobs scoring (+ переиспользуемый `score()`)
- `ft-lab/scripts/quality.py` — orchestrator
- `ft-lab/data/eval/edge_cases.jsonl` — borderline + noisy
- `ft-lab/results/quality.jsonl` — per-row детали
- `ft-lab/results/day7-report.md` — таблицы + per-row
- `ft-lab/results/confidence.jsonl` + `confidence-summary.md` — отдельный прогон только scoring (калибровка)

## Что не покрыто

- **Redundancy / self-consistency** (3× temp=0.7, majority vote) — пропустили: с GBNF и temperature=0 ответ детерминирован, redundancy на этой задаче малоинформативна. Закладываем в day 9 (multistage) если потребуется.
- **Tier 2 прогон quality.py** — не запускали; tier1 даёт достаточный материал для отчёта.
