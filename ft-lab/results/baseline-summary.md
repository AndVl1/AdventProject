# Baseline (день 6)

Eval: 10 примеров. grammar=True

| metric | tier1 base 3B | tier2 teacher |
|--------|---------------|---------------|
| parsed_ok | 10/10 (100%) | 10/10 (100%) |
| category_acc | 2/10 (20%) | 3/10 (30%) |
| sentiment_acc | 9/10 (90%) | 9/10 (90%) |
| avg_latency_ms | 438 | 708 |
| avg_tokens_out | 14.4 | 14.2 |

Per-row: см. `results/baseline.jsonl`.