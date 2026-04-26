# День 10 — Cascade report (baseline_no_t0)

Eval: **30** примеров (10 valid + 10 edge + 10 long).

Конфиг: T0=off threshold=0.15 | T1 inner=noslfchk_nogbnf_thr095

## Сводка

| метрика | значение |
|---|---|
| acc category | 70.0% |
| acc sentiment | 86.7% |
| acc both | 60.0% |
| avg latency (ms) | 637.8 |
| p50 latency (ms) | 504.5 |
| max latency (ms) | 1474.7 |
| total tokens | 13739 |
| решено T0 | 0 |
| решено T1 | 24 |
| решено T2 | 6 |

## По подмножествам (acc both)

| kind | acc | n |
|---|---|---|
| correct | 30.0% | 10 |
| borderline | 60.0% | 5 |
| noisy | 100.0% | 5 |
| long | 70.0% | 10 |
