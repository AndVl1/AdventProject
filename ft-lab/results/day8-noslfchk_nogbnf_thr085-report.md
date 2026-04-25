# День 8 — Routing report (noslfchk_nogbnf_thr085)

Total: **30** примеров (10 valid + 10 edge + 10 long).

## Конфигурация прогона
```json
{
  "tag": "noslfchk_nogbnf_thr085",
  "use_selfcheck": false,
  "use_scoring": true,
  "scoring_no_grammar": true,
  "escalate_threshold": 0.85,
  "tier1_thinking": false,
  "tier2_thinking": false
}
```

## Сводка router (tier1 → tier2 при сигналах)

| метрика | router | baseline (all-tier2) |
|---------|:------:|:--------------------:|
| joint accuracy | 56.7% | 63.3% |
| category accuracy | 70.0% | 73.3% |
| sentiment accuracy | 83.3% | 86.7% |
| avg wall latency | 597.2 ms | 533.1 ms |
| p50 / p95 latency | 486.6 / 1291.8 ms | 533.3 / 696.9 ms |
| total tokens | 13624 | 6143 |

## Маршрутизация
- Остался на Tier 1: **25/30** (83%)
- Эскалировано на Tier 2: **5/30** (17%)

### Причины эскалации
| reason | count |
|--------|------:|
| `tier1_ok` | 25 |
| `scoring_lowprob(0.63)` | 2 |
| `scoring_fail` | 1 |
| `scoring_lowprob(0.81)` | 1 |
| `scoring_lowprob(0.66)` | 1 |

## По типам входа

| kind | n | joint_acc | tier1_only | escalated | avg_lat ms |
|------|---|-----------|------------|-----------|-----------:|
| correct | 10 | 30% | 7 | 3 | 625.8 |
| borderline | 5 | 40% | 5 | 0 | 456.3 |
| noisy | 5 | 100% | 4 | 1 | 592.6 |
| long | 10 | 70% | 9 | 1 | 641.2 |

## Per-row

| id | kind | tier1 signals | escalated | reason | final | correct (cat/sent) |
|----|------|---------------|-----------|--------|-------|--------------------|
| synth/bug/pos/00 | correct | con=OK, sc=UNSURE(0.6271), slc=None/agree=None | **T2** | `scoring_lowprob(0.63)` | tier2 | ✓/✓ |
| synth/other/neu/01 | correct | con=OK, sc=OK(0.9999), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✗/✗ |
| synth/billing/pos/04 | correct | con=OK, sc=FAIL(0.2131), slc=None/agree=None | **T2** | `scoring_fail` | tier2 | ✗/✓ |
| synth/how_to/pos/03 | correct | con=OK, sc=OK(0.999), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| synth/bug/neg/03 | correct | con=OK, sc=OK(1.0), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| synth/other/neg/04 | correct | con=OK, sc=OK(0.998), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✗/✓ |
| synth/billing/pos/01 | correct | con=OK, sc=UNSURE(0.8065), slc=None/agree=None | **T2** | `scoring_lowprob(0.81)` | tier2 | ✗/✓ |
| synth/other/pos/04 | correct | con=OK, sc=OK(0.9718), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✗/✓ |
| synth/how_to/neg/02 | correct | con=OK, sc=OK(1.0), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✗/✓ |
| synth/feature_request/neg/03 | correct | con=OK, sc=OK(1.0), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✗/✓ |
| edge/borderline/01 | borderline | con=OK, sc=OK(0.9791), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/borderline/02 | borderline | con=OK, sc=OK(0.9992), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✗/✓ |
| edge/borderline/03 | borderline | con=OK, sc=OK(0.9228), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✗ |
| edge/borderline/04 | borderline | con=OK, sc=OK(1.0), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/borderline/05 | borderline | con=OK, sc=OK(0.9955), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✗ |
| edge/noisy/01 | noisy | con=OK, sc=OK(1.0), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/noisy/02 | noisy | con=OK, sc=OK(1.0), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/noisy/03 | noisy | con=OK, sc=OK(0.9999), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/noisy/04 | noisy | con=OK, sc=UNSURE(0.6638), slc=None/agree=None | **T2** | `scoring_lowprob(0.66)` | tier2 | ✓/✓ |
| edge/noisy/05 | noisy | con=OK, sc=OK(0.9969), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/long/01 | long | con=OK, sc=OK(1.0), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/long/02 | long | con=OK, sc=OK(0.9841), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/long/03 | long | con=OK, sc=OK(0.9999), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✗ |
| edge/long/04 | long | con=OK, sc=UNSURE(0.6303), slc=None/agree=None | **T2** | `scoring_lowprob(0.63)` | tier2 | ✓/✗ |
| edge/long/05 | long | con=OK, sc=OK(0.9999), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/long/06 | long | con=OK, sc=OK(0.9999), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✗/✓ |
| edge/long/07 | long | con=OK, sc=OK(0.9999), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/long/08 | long | con=OK, sc=OK(0.9759), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/long/09 | long | con=OK, sc=OK(1.0), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |
| edge/long/10 | long | con=OK, sc=OK(1.0), slc=None/agree=None | T1 | `tier1_ok` | tier1 | ✓/✓ |

Детали per-row: `results/router-noslfchk_nogbnf_thr085.jsonl`.
Baseline per-row: `results/router_baseline-noslfchk_nogbnf_thr085.jsonl`.