# День 9 — Multi-stage report (mm25-remote)

Eval: **20** примеров (`data/eval/day9_eval.jsonl`).

Поля: category, sentiment, severity, affected_component (Jaccard ≥0.25), suggested_action.

## Сводка

| метрика | mono | multi |
|---|---|---|
| all-fields acc | 20.0% | 0.0% |
| acc category | 35.0% | 0.0% |
| acc sentiment | 30.0% | 10.0% |
| acc severity | 25.0% | 0.0% |
| acc affected_component | 30.0% | 15.0% |
| acc suggested_action | 30.0% | 15.0% |
| avg latency (ms) | 3147.4 | 14577.9 |
| p50 latency (ms) | 3337.4 | 13392.0 |
| max latency (ms) | 6060.9 | 26341.9 |
| total tokens | 9120 | 41931 |
| tier1 calls | 20 | 100 |
| tier2 calls | 0 | 0 |
