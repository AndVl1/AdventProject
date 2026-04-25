# День 9 — Multi-stage report (tier2)

Eval: **20** примеров (`data/eval/day9_eval.jsonl`).

Поля: category, sentiment, severity, affected_component (Jaccard ≥0.25), suggested_action.

## Сводка

| метрика | mono | multi |
|---|---|---|
| all-fields acc | 25.0% | 35.0% |
| acc category | 95.0% | 85.0% |
| acc sentiment | 90.0% | 90.0% |
| acc severity | 70.0% | 70.0% |
| acc affected_component | 55.0% | 60.0% |
| acc suggested_action | 75.0% | 85.0% |
| avg latency (ms) | 1476.7 | 4732.5 |
| p50 latency (ms) | 1426.0 | 4792.6 |
| max latency (ms) | 3185.9 | 5949.2 |
| total tokens | 6232 | 30566 |
| tier1 calls | 20 | 97 |
| tier2 calls | 0 | 3 |
