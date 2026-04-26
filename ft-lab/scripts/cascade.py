"""День 10 — Cascade Tier 0 → Tier 1 → Tier 2.

Расширение router.py: перед T1 добавляем T0 — TF-IDF + LogReg классификатор
(scripts/tier0_tfidf.py). Если max(proba) < threshold — эскалация в T1.
Дальше — обычная логика дня 8 (T1 → T2).

CLI:
    python scripts/cascade.py                        # дефолт: T0=0.7, T1 noslfchk_nogbnf_thr095
    python scripts/cascade.py --t0-threshold 0.5     # ablation
    python scripts/cascade.py --t0-threshold 0.9     # ablation
    python scripts/cascade.py --no-t0                # baseline без T0 (только T1→T2)
    python scripts/cascade.py --tag custom

Тестовый набор тот же что в день 8 (30 примеров: 10 valid + 10 edge + 10 long).
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dotenv import load_dotenv

import tier0_tfidf
from router import (
    RunConfig,
    Tier,
    build_eval_set,
    build_tiers,
    decide_escalate,
    is_correct,
)

load_dotenv()

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"


@dataclass
class CascadeConfig:
    """Расширяет RunConfig сигналами эскалации T0→T1.

    Сигнал — `min_margin` (минимум по category/sentiment top1-top2 margin).
    Margin стабильнее max_proba: ловит размазанное распределение даже когда
    top1 высокий по абсолюту.
    """
    use_t0: bool = True
    t0_threshold: float = 0.15   # min_margin < threshold → эскалация в T1
    inner: RunConfig = field(default_factory=RunConfig)
    tag: str = ""

    def auto_tag(self) -> str:
        if self.tag:
            return self.tag
        if not self.use_t0:
            return "no_t0_" + self.inner.auto_tag()
        return f"t0_mrg{int(self.t0_threshold * 100):02d}_{self.inner.auto_tag()}"

    def out_jsonl(self) -> Path:
        return RESULTS / f"day10-cascade-{self.auto_tag()}.jsonl"

    def out_report(self) -> Path:
        return RESULTS / f"day10-{self.auto_tag()}-report.md"


def run_cascade(item: dict, t0_model: dict | None, t1: Tier, t2: Tier, cfg: CascadeConfig) -> dict:
    """Один пример через каскад T0 → T1 → T2."""
    user = item["user"]
    expected = item["expected"]
    calls: list[dict] = []
    tier_used = ""
    parsed: dict | None = None
    route_reason = ""
    total_lat = 0.0
    total_in = 0
    total_out = 0

    # ---- T0 ----
    t0_pred_dict = None
    if cfg.use_t0 and t0_model is not None:
        t0_pred = tier0_tfidf.predict(user, t0_model)
        t0_pred_dict = {
            "category": t0_pred.category,
            "sentiment": t0_pred.sentiment,
            "cat_proba": round(t0_pred.cat_proba, 3),
            "sent_proba": round(t0_pred.sent_proba, 3),
            "cat_margin": round(t0_pred.cat_margin, 3),
            "sent_margin": round(t0_pred.sent_margin, 3),
            "min_margin": round(t0_pred.min_margin, 3),
            "latency_ms": t0_pred.latency_ms,
        }
        calls.append({"stage": "tier0", "tier": "tier0", **t0_pred_dict})
        total_lat += t0_pred.latency_ms

        if t0_pred.min_margin >= cfg.t0_threshold:
            # T0 уверен — стопаем на T0
            parsed = {"category": t0_pred.category, "sentiment": t0_pred.sentiment}
            tier_used = "tier0"
            route_reason = f"t0_confident(margin={t0_pred.min_margin:.2f})"
            return _wrap_result(item, expected, parsed, tier_used, route_reason, calls,
                                total_lat, total_in, total_out)
        else:
            route_reason = f"t0_lowmargin({t0_pred.min_margin:.2f})→t1"

    # ---- T1 ----
    t1_res = t1.predict(user, with_signals=True, cfg=cfg.inner)
    calls.append({
        "stage": "tier1",
        "tier": "tier1",
        "latency_ms": t1_res["latency_ms"],
        "tokens_in": t1_res["tokens_in"],
        "tokens_out": t1_res["tokens_out"],
        "constraint": t1_res["constraint"]["status"],
        "scoring_min_prob": (t1_res["scoring"] or {}).get("min_prob"),
    })
    total_lat += t1_res["latency_ms"]
    total_in += t1_res["tokens_in"]
    total_out += t1_res["tokens_out"]

    escalate, esc_reason = decide_escalate(t1_res, cfg.inner)
    if not escalate:
        parsed = t1_res["parsed"]
        tier_used = "tier1"
        route_reason = (route_reason + " | " if route_reason else "") + esc_reason
        return _wrap_result(item, expected, parsed, tier_used, route_reason, calls,
                            total_lat, total_in, total_out)

    # ---- T2 ----
    t2_res = t2.predict(user, with_signals=False)
    calls.append({
        "stage": "tier2",
        "tier": "tier2",
        "latency_ms": t2_res["latency_ms"],
        "tokens_in": t2_res["tokens_in"],
        "tokens_out": t2_res["tokens_out"],
        "constraint": t2_res["constraint"]["status"],
    })
    total_lat += t2_res["latency_ms"]
    total_in += t2_res["tokens_in"]
    total_out += t2_res["tokens_out"]
    parsed = t2_res["parsed"]
    tier_used = "tier2"
    route_reason = (route_reason + " | " if route_reason else "") + esc_reason
    return _wrap_result(item, expected, parsed, tier_used, route_reason, calls,
                        total_lat, total_in, total_out)


def _wrap_result(item, expected, parsed, tier_used, route_reason, calls,
                 total_lat, total_in, total_out) -> dict:
    cat_ok, sent_ok = is_correct(parsed, expected)
    return {
        "id": item.get("id", ""),
        "kind": item.get("kind", ""),
        "expected": expected,
        "parsed": parsed,
        "match": {"category": cat_ok, "sentiment": sent_ok, "all": cat_ok and sent_ok},
        "tier_used": tier_used,
        "route_reason": route_reason,
        "total_latency_ms": round(total_lat, 1),
        "total_tokens_in": total_in,
        "total_tokens_out": total_out,
        "total_tokens": total_in + total_out,
        "calls": calls,
    }


def _summarize(rows: list[dict]) -> dict:
    n = len(rows)
    cat = sum(1 for r in rows if r["match"]["category"])
    sent = sum(1 for r in rows if r["match"]["sentiment"])
    both = sum(1 for r in rows if r["match"]["all"])
    tiers_used = Counter(r["tier_used"] for r in rows)
    lats = [r["total_latency_ms"] for r in rows]
    tokens = sum(r["total_tokens"] for r in rows)
    return {
        "n": n,
        "acc_category": round(100 * cat / n, 1),
        "acc_sentiment": round(100 * sent / n, 1),
        "acc_all": round(100 * both / n, 1),
        "avg_latency_ms": round(statistics.mean(lats), 1),
        "p50_latency_ms": round(statistics.median(lats), 1),
        "max_latency_ms": round(max(lats), 1),
        "total_tokens": tokens,
        "tier0_solved": tiers_used.get("tier0", 0),
        "tier1_solved": tiers_used.get("tier1", 0),
        "tier2_solved": tiers_used.get("tier2", 0),
    }


def write_report(cfg: CascadeConfig, summary: dict, items_by_kind: dict) -> None:
    cfg.out_report().parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f"# День 10 — Cascade report ({cfg.auto_tag()})",
        "",
        f"Eval: **{summary['n']}** примеров (10 valid + 10 edge + 10 long).",
        "",
        f"Конфиг: T0={'on' if cfg.use_t0 else 'off'} threshold={cfg.t0_threshold} | "
        f"T1 inner={cfg.inner.auto_tag()}",
        "",
        "## Сводка",
        "",
        "| метрика | значение |",
        "|---|---|",
        f"| acc category | {summary['acc_category']}% |",
        f"| acc sentiment | {summary['acc_sentiment']}% |",
        f"| acc both | {summary['acc_all']}% |",
        f"| avg latency (ms) | {summary['avg_latency_ms']} |",
        f"| p50 latency (ms) | {summary['p50_latency_ms']} |",
        f"| max latency (ms) | {summary['max_latency_ms']} |",
        f"| total tokens | {summary['total_tokens']} |",
        f"| решено T0 | {summary['tier0_solved']} |",
        f"| решено T1 | {summary['tier1_solved']} |",
        f"| решено T2 | {summary['tier2_solved']} |",
        "",
        "## По подмножествам (acc both)",
        "",
        "| kind | acc | n |",
        "|---|---|---|",
    ]
    for kind, rows in items_by_kind.items():
        if not rows:
            continue
        n = len(rows)
        both = sum(1 for r in rows if r["match"]["all"])
        lines.append(f"| {kind} | {round(100 * both / n, 1)}% | {n} |")
    lines.append("")
    cfg.out_report().write_text("\n".join(lines), encoding="utf-8")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--no-t0", action="store_true", help="отключить T0 (baseline T1→T2)")
    p.add_argument("--t0-threshold", type=float, default=0.15,
                   help="порог min_margin (top1-top2). По дефолту 0.15.")
    # inner RunConfig — повторяем дефолт-победитель дня 8: noslfchk_nogbnf_thr095.
    p.add_argument("--no-selfcheck", action="store_true", default=True,
                   help="(default) выкинуть selfcheck из T1")
    p.add_argument("--with-selfcheck", action="store_true",
                   help="включить selfcheck (отменяет --no-selfcheck)")
    p.add_argument("--scoring-no-grammar", action="store_true", default=True,
                   help="(default) scoring без GBNF — честные logprobs")
    p.add_argument("--with-grammar", action="store_true",
                   help="scoring с GBNF (отменяет --scoring-no-grammar)")
    p.add_argument("--escalate-threshold", type=float, default=0.95,
                   help="(default 0.95) порог scoring.min_prob для T1→T2")
    p.add_argument("--tag", default="")
    args = p.parse_args()

    inner = RunConfig(
        use_selfcheck=args.with_selfcheck,
        use_scoring=True,
        scoring_no_grammar=not args.with_grammar,
        escalate_threshold=args.escalate_threshold,
        tier1_thinking=False,
        tier2_thinking=False,
    )
    cfg = CascadeConfig(
        use_t0=not args.no_t0,
        t0_threshold=args.t0_threshold,
        inner=inner,
        tag=args.tag,
    )

    print(f"[cascade] config: {cfg.auto_tag()}", file=sys.stderr)

    t0_model = tier0_tfidf.load_model() if cfg.use_t0 else None
    t1, t2 = build_tiers(inner)

    items = build_eval_set(n_correct=10)
    print(f"[cascade] {len(items)} items", file=sys.stderr)

    rows = []
    by_kind: dict[str, list] = {}
    t_start = time.perf_counter()
    for i, item in enumerate(items, 1):
        r = run_cascade(item, t0_model, t1, t2, cfg)
        rows.append(r)
        by_kind.setdefault(item.get("kind", ""), []).append(r)
        print(f"  [{i}/{len(items)}] {item.get('id')} → {r['tier_used']} "
              f"(cat={r['match']['category']} sent={r['match']['sentiment']}) "
              f"lat={r['total_latency_ms']}ms", file=sys.stderr)

    summary = _summarize(rows)
    summary["wall_time_sec"] = round(time.perf_counter() - t_start, 1)

    cfg.out_jsonl().parent.mkdir(parents=True, exist_ok=True)
    with cfg.out_jsonl().open("w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    write_report(cfg, summary, by_kind)
    print(f"\n[cascade] summary: {json.dumps(summary, ensure_ascii=False)}", file=sys.stderr)
    print(f"[cascade] details → {cfg.out_jsonl().relative_to(ROOT)}", file=sys.stderr)
    print(f"[cascade] report  → {cfg.out_report().relative_to(ROOT)}", file=sys.stderr)


if __name__ == "__main__":
    main()
