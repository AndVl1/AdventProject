# Day 8 — Routing Tier1 ↔ Tier2 (2026-04-25)

## Задача

Каскад двух llama-server'ов: Tier 1 (Qwen2.5-3B-Instruct) и Tier 2 (Qwen3.6-35B-A3B Q3_K_S). Эскалация по сигналам дня 7. Цель — снизить cost vs «всё на Tier 2» при минимальной просадке accuracy.

## Реализация — `ft-lab/scripts/router.py`

- `Tier(name, base_url, model, no_think)` — расширяемый интерфейс. День 10 добавит `Tier 0 = micro` в начало списка.
- `Tier.predict(text, with_signals=True/False)`: Tier1 гонит выбранные сигналы (constraint обязателен, scoring/selfcheck по cfg), Tier2 — только constraint.
- `decide_escalate(tier1_result, cfg)` — сигналы:
  - `constraint=FAIL` → `constraint_fail`
  - `scoring.min_prob < cfg.escalate_threshold` → `scoring_lowprob(...)`
  - `selfcheck.stage_b.agree=false` → `selfcheck_disagree`
- `RunConfig` — флаги ablation: `use_selfcheck`, `use_scoring`, `scoring_no_grammar`, `escalate_threshold`, `tier1_thinking`, `tier2_thinking`. Авто-генерация tag для имён отчётов.

## Тестовый набор (30 примеров)

- 10 valid из `train/valid.jsonl` (gold labels из дня 6).
- 10 edge_cases (5 borderline + 5 noisy) из дня 7.
- 10 long (1100-1760 символов): 3 bug, 2 billing, 2 feature_request, 2 how_to, 1 other.

## Результаты ablation (5 прогонов)

| конфигурация | router acc | sent acc | router lat (ms) | router tokens | escalation rate |
|--------------|:---:|:---:|:---:|:---:|:---:|
| `default` (constraint+scoring+selfcheck, GBNF, thr=0.5) | 56.7% | 86.7% | 2108 | 32599 | 53% |
| `t2think` (default + thinking на Tier2) | 53.3% | 83.3% | 6493 | 48348 | 53% |
| `noslfchk` (без selfcheck) | 50.0% | 83.3% | **544** | **12859** | 3% |
| `noslfchk_nogbnf_thr085` (scoring без GBNF, thr=0.85) | 56.7% | 83.3% | 597 | 13624 | 17% |
| **`noslfchk_nogbnf_thr095`** (scoring без GBNF, thr=0.95) | **60.0%** | **86.7%** | 613 | 13739 | 20% |

Baseline (всё на Tier 2 без thinking): **63.3% acc / 528 ms / 6143 tokens**.

### Победитель: `noslfchk_nogbnf_thr095`

Vs `default-router`:
- Accuracy: 56.7% → **60.0%** (+3.3 п.п.)
- Sentiment: 86.7% → 86.7% (без потерь)
- Latency: 2108 → 613 ms (**-71%**)
- Tokens: 32599 → 13739 (**-58%**)
- Эскалация: 16/30 → 6/30 (все осмысленные — `scoring_lowprob` 0.63/0.66/0.81/0.92 + `scoring_fail` 0.21)

Vs `baseline (all-T2)`:
- Accuracy: 60.0% vs 63.3% (-3.3 п.п.)
- Latency: 613 vs 528 ms (+16%)
- Tokens: 13739 vs 6143 (+2.2×)

### Per-subset (`thr095` vs `default`)

| kind | default | thr095 | Δ |
|------|:-------:|:------:|:-:|
| correct | 30% | 30% | 0 (потолок данных) |
| borderline | 60% | 60% | 0 |
| noisy | 80% | **100%** | **+20** |
| long | 70% | 70% | 0 |

Ключевая эскалация: `edge/borderline/03` с `min_prob=0.92` → T2 → правильный ответ. Эту границу `thr085` пропускал.

## Анализ

### 1. Selfcheck выкинуть из online (подтверждено двумя прогонами)

`default` vs `noslfchk`: эскалация 53% → 3%, latency -74%, accuracy -6.7 п.п. Selfcheck даёт сигнал, но шумный — 15/30 эскалаций, из которых половина без выигрыша на correct subset. Tier1 пайплайн (3 LLM-вызова) сам в 2× медленнее одного Tier2.

### 2. GBNF маскирует logprobs

С GBNF `scoring.min_prob` почти всегда ≥0.95 — порог 0.5 не срабатывает (1/30 эскалаций). Без GBNF честные logprobs дают разброс 0.20-0.99 — порог становится осмысленным.

### 3. Thinking на Tier2 — отрицательный сигнал

Reasoning ухудшил accuracy на 3.3 п.п. для router и 3.3 п.п. для baseline. На noisy просел особенно (-20 п.п.) — модель оверпарсит эмоджи и опечатки. Latency baseline +14.5×.

### 4. Синтетика correct гнилая

На correct subset acc=30% во всех прогонах включая baseline — потолок данных, не моделей. Никакая конфигурация не починит gold-labels.

### 5. Router pareto-проигрывает baseline на этой задаче

Tier2 35B-A3B Q3_K_S без thinking — 528 ms на запрос. Tier1 + сигналы — 500 ms. Эскалация добавляет 20% запросов. Router при таких latency не окупается.

**Каскад имеет смысл** когда:
- Tier2 действительно дорог (платный API, thinking-enabled, бóльшая модель).
- Большинство запросов простые (≥80% не требуют эскалации).

В нашем сценарии оба условия не выполнены.

## Решения для дня 9 / 10

1. **Online-сигнал**: только scoring без GBNF + threshold 0.95. Selfcheck выкинуть из online, оставить для оффлайн калибровки.
2. **Day 9 multistage**: использовать ту же эскалационную логику между этапами 1 (нормализация) и 3 (extraction), не между моделями.
3. **Day 10 micro-tier**: micro станет Tier 0 — самый дешёвый. На нём те же сигналы (lowprob threshold) для эскалации в Tier 1. Архитектурно расширение списка `tiers` в router.py — одна строка.
4. **Перед днём 9 пересобрать correct-subset вручную** (10 примеров, час работы) — иначе потолок 30% acc.

## Что не покрыто

- **Селективный selfcheck** (запускать только когда scoring.min_prob в [0.7, 0.9]) — потенциально лучший trade-off, но требует условной логики, не однострочный флаг.
- **Tier2 c reasoning на эскалациях** — может выправить accuracy на borderline, но дорого. Не приоритет.
- **Воспроизведение на ≥100 примерах** — текущие 30 шумные, разброс между прогонами 3-5 п.п.

## Артефакты

- `ft-lab/scripts/router.py` — Tier + RunConfig + ablation flags
- `ft-lab/data/eval/long_cases.jsonl` — 10 длинных тикетов
- `ft-lab/results/router-{tag}.jsonl`, `router_baseline-{tag}.jsonl` — per-row для каждого ablation
- `ft-lab/results/day8-{tag}-report.md` — таблицы для каждого прогона
- 5 ablation tag'ов: `default`, `t2think`, `noslfchk`, `noslfchk_nogbnf_thr085`, `noslfchk_nogbnf_thr095`

## Вывод одной строкой

Лучшая online-конфигурация: `--no-selfcheck --scoring-no-grammar --escalate-threshold 0.95`. Accuracy +3.3 п.п. над default-router, latency -71%, tokens -58%. Однако baseline (всё на Tier2) всё равно лучше по cost/accuracy на текущей задаче — каскад не оправдан, пока Tier2 не станет реально дороже Tier1 (день 10 micro-tier должен это исправить).
