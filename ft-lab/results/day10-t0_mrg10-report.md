# День 10 — Cascade report (t0_mrg10)

Eval: **30** примеров (10 valid + 10 edge + 10 long).

Конфиг: T0=on threshold=0.1 | T1 inner=noslfchk_nogbnf_thr095

## Сводка

| метрика | значение |
|---|---|
| acc category | 90.0% |
| acc sentiment | 83.3% |
| acc both | 80.0% |
| avg latency (ms) | 620.6 |
| p50 latency (ms) | 467.7 |
| max latency (ms) | 7459.4 |
| total tokens | 9578 |
| решено T0 | 12 |
| решено T1 | 16 |
| решено T2 | 2 |

## По подмножествам (acc both)

| kind | acc | n |
|---|---|---|
| correct | 100.0% | 10 |
| borderline | 40.0% | 5 |
| noisy | 100.0% | 5 |
| long | 70.0% | 10 |
