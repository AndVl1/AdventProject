# День 9 — Multi-stage report (4omini)

Eval: **20** примеров (`data/eval/day9_eval.jsonl`).

Поля: category, sentiment, severity, affected_component (Jaccard ≥0.25), suggested_action.

## Сводка

| метрика | mono | multi |
|---|---|---|
| all-fields acc | 10.0% | 25.0% |
| acc category | 95.0% | 90.0% |
| acc sentiment | 80.0% | 85.0% |
| acc severity | 55.0% | 60.0% |
| acc affected_component | 50.0% | 55.0% |
| acc suggested_action | 60.0% | 80.0% |
| avg latency (ms) | 929.4 | 3934.7 |
| p50 latency (ms) | 921.7 | 3813.4 |
| max latency (ms) | 1551.7 | 5259.3 |
| total tokens | 5745 | 28964 |
| tier1 calls | 20 | 100 |
| tier2 calls | 0 | 0 |
