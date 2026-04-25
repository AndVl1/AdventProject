# День 9 — Multi-stage report (default)

Eval: **20** примеров (`data/eval/day9_eval.jsonl`).

Поля: category, sentiment, severity, affected_component (Jaccard ≥0.25), suggested_action.

## Сводка

| метрика | mono | multi |
|---|---|---|
| all-fields acc | 10.0% | 15.0% |
| acc category | 95.0% | 90.0% |
| acc sentiment | 75.0% | 70.0% |
| acc severity | 60.0% | 65.0% |
| acc affected_component | 65.0% | 55.0% |
| acc suggested_action | 40.0% | 50.0% |
| avg latency (ms) | 580.5 | 2554.9 |
| p50 latency (ms) | 511.4 | 2393.3 |
| max latency (ms) | 1334.8 | 7501.2 |
| total tokens | 6610 | 33213 |
| tier1 calls | 20 | 94 |
| tier2 calls | 0 | 6 |
