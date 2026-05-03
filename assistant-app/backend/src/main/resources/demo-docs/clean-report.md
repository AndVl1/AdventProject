# Q4 2025 Operations Report

## Summary
Throughput grew 18% QoQ. Latency p95 stable at 230ms. Two incidents (SEV-2),
both resolved within SLA. Headcount unchanged.

## Risks
- Single-region dependency for payments provider
- Postgres primary on shared hardware with analytics workload

## Next quarter
- Migrate payments to multi-region
- Split analytics replica off primary
