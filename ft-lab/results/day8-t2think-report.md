# День 8 — Routing report (t2think)

Total: **30** примеров (10 valid + 10 edge + 10 long).

## Конфигурация прогона
```json
{
  "tag": "t2think",
  "use_selfcheck": true,
  "use_scoring": true,
  "scoring_no_grammar": false,
  "escalate_threshold": 0.5,
  "tier1_thinking": false,
  "tier2_thinking": true
}
```

## Сводка router (tier1 → tier2 при сигналах)

| метрика | router | baseline (all-tier2) |
|---------|:------:|:--------------------:|
| joint accuracy | 53.3% | 60.0% |
| category accuracy | 66.7% | 73.3% |
| sentiment accuracy | 83.3% | 83.3% |
| avg wall latency | 6493.1 ms | 9402.7 ms |
| p50 / p95 latency | 10827.6 / 11444.8 ms | 9366.2 / 9950.7 ms |
| total tokens | 48348 | 36786 |

## Маршрутизация
- Остался на Tier 1: **14/30** (47%)
- Эскалировано на Tier 2: **16/30** (53%)

### Причины эскалации
| reason | count |
|--------|------:|
| `selfcheck_disagree` | 15 |
| `tier1_ok` | 14 |
| `scoring_fail` | 1 |

## По типам входа

| kind | n | joint_acc | tier1_only | escalated | avg_lat ms |
|------|---|-----------|------------|-----------|-----------:|
| correct | 10 | 30% | 3 | 7 | 8202.1 |
| borderline | 5 | 60% | 2 | 3 | 7026.9 |
| noisy | 5 | 60% | 3 | 2 | 5041.9 |
| long | 10 | 70% | 6 | 4 | 5242.7 |

## Per-row

| id | kind | tier1 signals | escalated | reason | final | correct (cat/sent) |
|----|------|---------------|-----------|--------|-------|--------------------|
| synth/bug/pos/00 | correct | con=OK, sc=UNSURE(0.626), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✓ |
| synth/other/neu/01 | correct | con=OK, sc=OK(0.9999), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✗/✗ |
| synth/billing/pos/04 | correct | con=OK, sc=FAIL(0.2106), slc=OK/agree=True | **T2** | `scoring_fail` | tier2 | ✗/✓ |
| synth/how_to/pos/03 | correct | con=OK, sc=OK(0.999), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✓/✓ |
| synth/bug/neg/03 | correct | con=OK, sc=OK(1.0), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✓ |
| synth/other/neg/04 | correct | con=OK, sc=OK(0.9981), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✗/✓ |
| synth/billing/pos/01 | correct | con=OK, sc=UNSURE(0.8072), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✗/✓ |
| synth/other/pos/04 | correct | con=OK, sc=OK(0.972), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✗/✓ |
| synth/how_to/neg/02 | correct | con=OK, sc=OK(1.0), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✗/✓ |
| synth/feature_request/neg/03 | correct | con=OK, sc=OK(1.0), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✗/✓ |
| edge/borderline/01 | borderline | con=OK, sc=OK(0.9792), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✓ |
| edge/borderline/02 | borderline | con=OK, sc=OK(0.9992), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✗/✓ |
| edge/borderline/03 | borderline | con=OK, sc=OK(0.9229), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✗ |
| edge/borderline/04 | borderline | con=OK, sc=OK(1.0), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/borderline/05 | borderline | con=OK, sc=OK(0.9955), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✓ |
| edge/noisy/01 | noisy | con=OK, sc=OK(1.0), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/noisy/02 | noisy | con=OK, sc=OK(1.0), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/noisy/03 | noisy | con=OK, sc=OK(0.9999), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✓ |
| edge/noisy/04 | noisy | con=OK, sc=UNSURE(0.6668), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✗/✓ |
| edge/noisy/05 | noisy | con=OK, sc=OK(0.9969), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✗ |
| edge/long/01 | long | con=OK, sc=OK(1.0), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✓ |
| edge/long/02 | long | con=OK, sc=OK(0.9841), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/long/03 | long | con=OK, sc=OK(0.9999), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✓/✗ |
| edge/long/04 | long | con=OK, sc=UNSURE(0.6308), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✗ |
| edge/long/05 | long | con=OK, sc=OK(0.9999), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/long/06 | long | con=OK, sc=OK(0.9999), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✗/✓ |
| edge/long/07 | long | con=OK, sc=OK(0.9999), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/long/08 | long | con=OK, sc=OK(0.9759), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✓ |
| edge/long/09 | long | con=OK, sc=OK(1.0), slc=UNSURE/agree=False | **T2** | `selfcheck_disagree` | tier2 | ✓/✓ |
| edge/long/10 | long | con=OK, sc=OK(1.0), slc=OK/agree=True | T1 | `tier1_ok` | tier1 | ✓/✓ |

Детали per-row: `results/router-t2think.jsonl`.
Baseline per-row: `results/router_baseline-t2think.jsonl`.