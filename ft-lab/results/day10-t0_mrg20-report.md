# День 10 — Cascade report (t0_mrg20)

Eval: **30** примеров (10 valid + 10 edge + 10 long).

Конфиг: T0=on threshold=0.2 | T1 inner=noslfchk_nogbnf_thr095

## Сводка

| метрика | значение |
|---|---|
| acc category | 93.3% |
| acc sentiment | 90.0% |
| acc both | 83.3% |
| avg latency (ms) | 432.6 |
| p50 latency (ms) | 462.8 |
| max latency (ms) | 1556.3 |
| total tokens | 10159 |
| решено T0 | 10 |
| решено T1 | 17 |
| решено T2 | 3 |

## По подмножествам (acc both)

| kind | acc | n |
|---|---|---|
| correct | 100.0% | 10 |
| borderline | 60.0% | 5 |
| noisy | 100.0% | 5 |
| long | 70.0% | 10 |
