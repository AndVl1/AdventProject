# Day 8 — Routing Tier1 ↔ Tier2 (2026-04-25)

## Задача

Каскад двух llama-server'ов: дешёвый Tier 1 (Qwen2.5-3B) и тяжёлый Tier 2 (Qwen3.6-35B-A3B). Эскалация по сигналам дня 7 (constraint / scoring / selfcheck). Цель — снизить cost vs «всё на Tier 2» при минимальной просадке accuracy.

## Реализация — `ft-lab/scripts/router.py`

- `Tier(name, base_url, model, no_think)` — расширяемый интерфейс. День 10 добавит `Tier 0 = micro` в начало списка.
- `Tier.predict(text, with_signals=True/False)`:
  - Tier 1 — `with_signals=True`, гонит constraint + scoring + selfcheck (всё из дня 7).
  - Tier 2 — `with_signals=False`, только constraint (доверие выше, экономим запросы).
- `decide_escalate(tier1_result)` — сигналы только дня 7, без эвристики по длине:
  - `constraint=FAIL` → `constraint_fail`
  - `scoring.min_prob < 0.5` → `scoring_lowprob(...)` (порог = граница UNSURE/FAIL дня 7)
  - `selfcheck.stage_b.agree=false` → `selfcheck_disagree`
- **fix selfcheck НЕ применяется** локально (вывод дня 7: маленькая модель — плохой судья).

## Тестовый набор (30 примеров)

- 10 valid из `train/valid.jsonl` (gold labels из дня 6).
- 10 edge_cases (5 borderline + 5 noisy) из дня 7.
- 10 long (1100-1760 символов JSON-строки): 3 bug, 2 billing, 2 feature_request, 2 how_to, 1 other. Реалистичные кейсы — bug-репорты со stack trace, billing с историей, how-to про API.

## Результаты

### Сводка router vs baseline (всё на Tier 2)

| метрика | router | baseline | Δ |
|---------|:------:|:--------:|:--:|
| joint accuracy | 56.7% | 63.3% | **-6.6 п.п.** |
| category accuracy | 66.7% | 73.3% | -6.6 п.п. |
| sentiment accuracy | 86.7% | 86.7% | 0 |
| avg wall latency | 2108 ms | 650 ms | **+3.2× медленнее** |
| p50 / p95 latency | 2018 / 3561 ms | 590 / 965 ms | хуже по обоим |
| total tokens | 32599 | 6143 | **+5.3× дороже** |

**Router проиграл по всем метрикам, кроме sentiment (равенство).**

### Маршрутизация

- На Tier 1 осталось: 14/30 (47%)
- Эскалировано на Tier 2: 16/30 (53%)
- Причины:
  - `selfcheck_disagree`: **15** ← главный (и плохой) триггер
  - `scoring_fail`: 1
  - `constraint_fail`: 0
- На correct subset (10): эскалировано 7/10, из них 2/7 правильны после Tier 2.

### По типам входа

| kind | n | joint_acc router | joint_acc baseline | tier1_only |
|------|---|:----------------:|:------------------:|:----------:|
| correct | 10 | 30% | 30% | 3 |
| borderline | 5 | 60% | 80% | 2 |
| noisy | 5 | 80% | 100% | 3 |
| long | 10 | 70% | 70% | 6 |

Long лучше всего поймал Tier 1 (6/10 без эскалации, 70% acc) — структурированные тикеты с явной семантикой не путают модель.

### Latency breakdown

- Tier 1 (3 проверки): avg **1689 ms**, median 1458 ms, max 2649 ms.
- Tier 2 (1 constraint): avg **786 ms**, median 653 ms.
- **Один Tier 1 уже медленнее одного Tier 2.** Как только эскалируем — общее время > 2× от baseline.

## Анализ: почему router проиграл

### 1. Selfcheck — слишком триггерный (50% эскалаций без смысла)

15/30 `selfcheck_disagree`. На correct subset selfcheck сработал «не согласен» 7 раз — но gold-метки из дня 6 уже подтверждают что 7/10 синтетических correct реально неверно размечены (`category` промахи). Selfcheck **правильно** сомневается, но Tier 2 тоже не вытягивает — потолок данных, а не модели.

На borderline/noisy/long selfcheck эскалирует «по форме» — реагирует на любую двусмысленность, даже когда исходный ответ Tier 1 верен. Из 8 эскалаций на edge — 5 случаев когда Tier 1 уже был прав.

### 2. Tier 1 пайплайн (3 запроса) тяжелее чем Tier 2 (1 запрос)

| | latency | tokens |
|---|---|---|
| Tier 1 (3 проверки) | 1689 ms | ~1100 |
| Tier 2 (1 constraint) | 786 ms | ~200 |

Это инвертирует логику каскада: «дешёвый-сначала» оказывается **дороже** «дорогого-всегда». Корневая причина — selfcheck-этап дня 7 делает 2 LLM-вызова с GBNF, plus scoring отдельный вызов.

### 3. Синтетика correct неверно размечена

Baseline accuracy на correct subset — 30% (3/10). Это потолок самих данных. Никакой router не починит проблему gold-labels.

## Решения для следующего этапа

1. **Online-routing должен быть дешёвым**. Удалить selfcheck из Tier 1 пайплайна — оставить только scoring (logprobs, +0 токенов overhead, побочный продукт генерации). Constraint бесплатен (pass_first=20/20 в день 7). Целевой Tier 1 latency ≤ 250 ms.
2. **Эскалация — только по logprobs**: `min_prob < 0.85` → Tier 2. Чтобы не зависеть от шумного selfcheck.
3. **Selfcheck — только для калибровки оффлайн** (выбор thresholds), не в проде.
4. **Перед днём 9 пересобрать correct-subset вручную** или взять реальные тикеты с GitHub Issues (план дня 6 это позволяет).

## Что не покрыто

- **Tier 2 без сигналов на correct тоже даёт 30%** — значит проблема не в маленькой модели, а в данных. Это надо подсветить отдельно при ручной разметке.
- **`scoring_lowprob` ни разу не сработал** (порог 0.5 — слишком низкий для GBNF). Поднимать до 0.85 — но тогда эскалаций будет ~20/30, ещё хуже.
- **Дополнительный logprobs-проход без GBNF** (вывод дня 7 #3) — не делали, дорого без явной выгоды.

## Артефакты

- `ft-lab/scripts/router.py` — Tier + decide_escalate + run_router/run_baseline_tier2
- `ft-lab/data/eval/long_cases.jsonl` — 10 длинных тикетов
- `ft-lab/results/router_runs.jsonl` — 30 строк per-row router
- `ft-lab/results/router_baseline.jsonl` — 30 строк baseline (Tier 2)
- `ft-lab/results/day8-report.md` — таблицы

## Вывод одной строкой

Двухступенчатый каскад с тяжёлым Tier 1 пайплайном (3 проверки) **проигрывает** прямому походу на Tier 2 по latency/cost/accuracy. Day 9 multistage и Day 10 micro-tier должны строиться от **дешёвого** Tier 0/1, а selfcheck — оффлайн-инструмент калибровки, не online-сигнал.
