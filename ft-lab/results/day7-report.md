# День 7 — Quality report (tier 1)

Model: `qwen2.5-3b-base`. Total: **20** (correct=10, edge=10).

## Сводка пайплайна
- Отклонено финально (status != OK): **14/20**
- Потребовало ретрая (constraint): **0**
- Сработал fallback на GBNF: **0**
- Avg latency полного пайплайна: **1349.8 ms**
- Total tokens (in+out): **13358**

## Все 20

| method | n | OK | UNSURE | FAIL | joint_acc | category_acc | sentiment_acc | avg_lat ms | tokens (in+out) |
|--------|---|----|--------|------|-----------|--------------|---------------|------------|-----------------|
| constraint | 20 | 20 | 0 | 0 | 8/20 (40%) | 10/20 (50%) | 17/20 (85%) | 207.6 | 2695 |
| scoring | 20 | 16 | 3 | 1 | 8/20 (40%) | 10/20 (50%) | 17/20 (85%) | 267.4 | 2695 |
| selfcheck | 20 | 9 | 11 | 0 | 6/20 (30%) | 7/20 (35%) | 17/20 (85%) | 874.7 | 7968 |
| final | 20 | 6 | 14 | 0 | 6/20 (30%) | 7/20 (35%) | 17/20 (85%) | 1349.8 | 13358 |

## Correct (10 valid)

| method | n | OK | UNSURE | FAIL | joint_acc | category_acc | sentiment_acc | avg_lat ms | tokens (in+out) |
|--------|---|----|--------|------|-----------|--------------|---------------|------------|-----------------|
| constraint | 10 | 10 | 0 | 0 | 2/10 (20%) | 2/10 (20%) | 9/10 (90%) | 213.0 | 1558 |
| scoring | 10 | 7 | 2 | 1 | 2/10 (20%) | 2/10 (20%) | 9/10 (90%) | 262.7 | 1558 |
| selfcheck | 10 | 4 | 6 | 0 | 2/10 (20%) | 2/10 (20%) | 10/10 (100%) | 996.1 | 4543 |
| final | 10 | 2 | 8 | 0 | 2/10 (20%) | 2/10 (20%) | 10/10 (100%) | 1472.0 | 7659 |

## Borderline (5)

| method | n | OK | UNSURE | FAIL | joint_acc | category_acc | sentiment_acc | avg_lat ms | tokens (in+out) |
|--------|---|----|--------|------|-----------|--------------|---------------|------------|-----------------|
| constraint | 5 | 5 | 0 | 0 | 2/5 (40%) | 4/5 (80%) | 3/5 (60%) | 197.3 | 578 |
| scoring | 5 | 5 | 0 | 0 | 2/5 (40%) | 4/5 (80%) | 3/5 (60%) | 273.0 | 578 |
| selfcheck | 5 | 2 | 3 | 0 | 2/5 (40%) | 3/5 (60%) | 3/5 (60%) | 778.1 | 1742 |
| final | 5 | 2 | 3 | 0 | 2/5 (40%) | 3/5 (60%) | 3/5 (60%) | 1248.6 | 2898 |

## Noisy (5)

| method | n | OK | UNSURE | FAIL | joint_acc | category_acc | sentiment_acc | avg_lat ms | tokens (in+out) |
|--------|---|----|--------|------|-----------|--------------|---------------|------------|-----------------|
| constraint | 5 | 5 | 0 | 0 | 4/5 (80%) | 4/5 (80%) | 5/5 (100%) | 206.9 | 559 |
| scoring | 5 | 4 | 1 | 0 | 4/5 (80%) | 4/5 (80%) | 5/5 (100%) | 271.2 | 559 |
| selfcheck | 5 | 3 | 2 | 0 | 2/5 (40%) | 2/5 (40%) | 4/5 (80%) | 728.6 | 1683 |
| final | 5 | 2 | 3 | 0 | 2/5 (40%) | 2/5 (40%) | 4/5 (80%) | 1206.9 | 2801 |

## Per-row
| id | kind | constraint | scoring (min_p) | selfcheck | final | correct (cat/sent) |
|----|------|------------|------------------|-----------|-------|-----|
| synth/bug/pos/00 | correct | OK (a=1) | UNSURE (0.626) | UNSURE | **UNSURE** | ✓/✓ |
| synth/other/neu/01 | correct | OK (a=1) | OK (0.9999) | UNSURE | **UNSURE** | ✗/✓ |
| synth/billing/pos/04 | correct | OK (a=1) | FAIL (0.2133) | OK | **UNSURE** | ✗/✓ |
| synth/how_to/pos/03 | correct | OK (a=1) | OK (0.999) | OK | **OK** | ✓/✓ |
| synth/bug/neg/03 | correct | OK (a=1) | OK (1.0) | UNSURE | **UNSURE** | ✗/✓ |
| synth/other/neg/04 | correct | OK (a=1) | OK (0.998) | UNSURE | **UNSURE** | ✗/✓ |
| synth/billing/pos/01 | correct | OK (a=1) | UNSURE (0.807) | OK | **UNSURE** | ✗/✓ |
| synth/other/pos/04 | correct | OK (a=1) | OK (0.9717) | UNSURE | **UNSURE** | ✗/✓ |
| synth/how_to/neg/02 | correct | OK (a=1) | OK (1.0) | UNSURE | **UNSURE** | ✗/✓ |
| synth/feature_request/neg/03 | correct | OK (a=1) | OK (1.0) | OK | **OK** | ✗/✓ |
| edge/borderline/01 | borderline | OK (a=1) | OK (0.9789) | UNSURE | **UNSURE** | ✓/✓ |
| edge/borderline/02 | borderline | OK (a=1) | OK (0.9992) | OK | **OK** | ✗/✓ |
| edge/borderline/03 | borderline | OK (a=1) | OK (0.9226) | UNSURE | **UNSURE** | ✗/✗ |
| edge/borderline/04 | borderline | OK (a=1) | OK (1.0) | OK | **OK** | ✓/✓ |
| edge/borderline/05 | borderline | OK (a=1) | OK (0.9955) | UNSURE | **UNSURE** | ✓/✗ |
| edge/noisy/01 | noisy | OK (a=1) | OK (1.0) | OK | **OK** | ✓/✓ |
| edge/noisy/02 | noisy | OK (a=1) | OK (1.0) | OK | **OK** | ✓/✓ |
| edge/noisy/03 | noisy | OK (a=1) | OK (0.9999) | UNSURE | **UNSURE** | ✗/✓ |
| edge/noisy/04 | noisy | OK (a=1) | UNSURE (0.6687) | OK | **UNSURE** | ✗/✓ |
| edge/noisy/05 | noisy | OK (a=1) | OK (0.997) | UNSURE | **UNSURE** | ✗/✗ |

Детали per-row: `results/quality.jsonl`.