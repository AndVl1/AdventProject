# Confidence (день 7) — tier 1

Eval: 14 примеров. grammar=True. model=qwen2.5-3b-base

## Метод
logprobs=True, top_logprobs=5. Per-field prob = exp(sum logprob токенов значения).
min_prob = min(cat_prob, sent_prob) — главный индикатор для маршрутизатора.

## Калибровка category
| bucket | n | precision | avg_prob |
|--------|---|-----------|----------|
| [0.00, 0.50) | 1 | 0.00 | 0.212 |
| [0.50, 0.70) | 1 | 0.00 | 0.624 |
| [0.70, 0.85) | 1 | 0.00 | 0.804 |
| [0.85, 0.95) | 1 | 0.00 | 0.909 |
| [0.95, 1.00) | 10 | 0.30 | 0.996 |

ECE = **0.679**

Threshold для precision ≥ 0.85: **None**

## Калибровка sentiment
| bucket | n | precision | avg_prob |
|--------|---|-----------|----------|
| [0.00, 0.50) | 0 | — | — |
| [0.50, 0.70) | 0 | — | — |
| [0.70, 0.85) | 1 | 1.00 | 0.782 |
| [0.85, 0.95) | 0 | — | — |
| [0.95, 1.00) | 13 | 0.92 | 1.000 |

ECE = **0.087**

Threshold для precision ≥ 0.85: **0.782**

## Использование маршрутизатором
При `min_prob < threshold` → метка считается uncertain, эскалируем на tier 2 (день 8: routing). Threshold подбирается из таблицы выше под целевой precision.

Пер-row: см. `results/confidence.jsonl`.