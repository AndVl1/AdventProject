"""День 8 — Routing Tier1 → Tier2.

Кэскад двух llama-server'ов:
- Tier 1: Qwen2.5-3B-Instruct (FT-target дня 6) на :8080.
- Tier 2: Qwen3.6-35B-A3B (MoE, teacher) на :8081.

Сигналы эскалации (все из дня 7, без fast-path по длине):
- constraint.status == FAIL          (формат сломался)
- selfcheck.stage_b.agree == False   (модель не согласилась с самой собой)
- scoring.min_prob < UNSURE_THRESHOLD (низкая уверенность по logprobs)

Вывод дня 7: selfcheck.fix локально НЕ применяем — фикс маленькой модели часто хуже исходного.
Используем `agree=false` только как триггер эскалации.

Структура расширяемая: список `tiers: list[Tier]` — день 10 добавит Tier 0 (micro) в начало.

Использование:
    python scripts/router.py             # прогон 30 примеров (10 valid + 10 edge + 10 long)
    python scripts/router.py --baseline  # прогон только Tier 2 для сравнения accuracy
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dotenv import load_dotenv
from openai import OpenAI

from confidence import score as score_logprobs
from constraint import constraint_check
from selfcheck import selfcheck

load_dotenv()

ROOT = Path(__file__).resolve().parent.parent
VALID = ROOT / "data" / "valid.jsonl"
META = ROOT / "data" / "split_meta.json"
EDGE = ROOT / "data" / "eval" / "edge_cases.jsonl"
LONG = ROOT / "data" / "eval" / "long_cases.jsonl"
OUT = ROOT / "results" / "router_runs.jsonl"
REPORT = ROOT / "results" / "day8-report.md"

# Порог из дня 7: status=UNSURE начинается с min_prob < 0.85, FAIL < 0.5.
# Эскалируем только если действительно низкая уверенность — UNSURE сам по себе шумит.
SCORING_ESCALATE_THRESHOLD = 0.5


@dataclass
class Tier:
    name: str
    base_url: str
    model: str
    no_think: bool = False
    client: OpenAI = field(init=False)

    def __post_init__(self):
        self.client = OpenAI(base_url=self.base_url, api_key="local", timeout=240)

    def predict(self, user_text: str, *, with_signals: bool) -> dict:
        """Один прогон тира.

        with_signals=True: запускаем 3 проверки дня 7 (constraint + scoring + selfcheck).
                           Используется для Tier 1 — нам нужны сигналы эскалации.
        with_signals=False: только constraint (быстрее, меньше токенов).
                            Используется для Tier 2 — там доверие выше.
        """
        if with_signals:
            con = constraint_check(self.client, self.model, user_text, no_think=self.no_think)
            sc = score_logprobs(
                self.client, self.model, user_text,
                use_grammar=True, no_think=self.no_think,
            )
            slc = selfcheck(self.client, self.model, user_text, no_think=self.no_think)
            parsed = (
                (sc.get("parsed") if isinstance(sc.get("parsed"), dict) else None)
                or con.get("parsed")
                or slc.get("final")
            )
            tokens_in = con["tokens_in"] + sc["tokens_in"] + slc["tokens_in"]
            tokens_out = con["tokens_out"] + sc["tokens_out"] + slc["tokens_out"]
            latency_ms = con["latency_ms"] + sc["latency_ms"] + slc["latency_ms"]
            return {
                "tier": self.name,
                "parsed": parsed,
                "constraint": con,
                "scoring": sc,
                "selfcheck": slc,
                "latency_ms": round(latency_ms, 1),
                "tokens_in": tokens_in,
                "tokens_out": tokens_out,
            }
        else:
            con = constraint_check(self.client, self.model, user_text, no_think=self.no_think)
            return {
                "tier": self.name,
                "parsed": con.get("parsed"),
                "constraint": con,
                "scoring": None,
                "selfcheck": None,
                "latency_ms": con["latency_ms"],
                "tokens_in": con["tokens_in"],
                "tokens_out": con["tokens_out"],
            }


def decide_escalate(tier1_result: dict) -> tuple[bool, str]:
    """Решение об эскалации только по сигналам дня 7.

    Приоритет: constraint > scoring > selfcheck.
    """
    con = tier1_result["constraint"]
    sc = tier1_result["scoring"]
    slc = tier1_result["selfcheck"]

    if con["status"] == "FAIL":
        return True, "constraint_fail"

    # scoring может вернуть FAIL если parsed не получился — но это маловероятно после constraint=OK
    if sc and sc["status"] == "FAIL":
        return True, "scoring_fail"
    if sc and sc["min_prob"] < SCORING_ESCALATE_THRESHOLD:
        return True, f"scoring_lowprob({sc['min_prob']:.2f})"

    if slc and slc.get("stage_b") and slc["stage_b"].get("agree") is False:
        return True, "selfcheck_disagree"

    return False, "tier1_ok"


# ---------- eval helpers ----------

def load_correct(n: int) -> list[dict]:
    lines = [l for l in VALID.read_text(encoding="utf-8").splitlines() if l.strip()][:n]
    meta = json.loads(META.read_text(encoding="utf-8"))["valid"][:n]
    items = []
    for line, m in zip(lines, meta):
        obj = json.loads(line)
        msgs = obj["messages"]
        user = next(x for x in msgs if x["role"] == "user")["content"]
        items.append({
            "id": m["id"],
            "kind": "correct",
            "user": user,
            "expected": {"category": m["category"], "sentiment": m["sentiment"]},
        })
    return items


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def build_eval_set(n_correct: int) -> list[dict]:
    return load_correct(n_correct) + load_jsonl(EDGE) + load_jsonl(LONG)


def is_correct(parsed: dict | None, expected: dict) -> tuple[bool, bool]:
    if not parsed or not isinstance(parsed, dict):
        return False, False
    cat = parsed.get("category") == expected["category"]
    sent = parsed.get("sentiment") == expected["sentiment"]
    return cat, sent


def build_tiers() -> tuple[Tier, Tier]:
    t1 = Tier(
        name="tier1",
        base_url=os.getenv("TIER1_BASE_URL", "http://127.0.0.1:8080/v1"),
        model=os.getenv("TIER1_MODEL", "qwen2.5-3b"),
        no_think=False,
    )
    t2 = Tier(
        name="tier2",
        base_url=os.getenv("TIER2_BASE_URL", "http://127.0.0.1:8081/v1"),
        model=os.getenv("TIER2_MODEL", "qwen3.6-35b-a3b"),
        no_think=True,
    )
    return t1, t2


# ---------- main run modes ----------

def run_router(items: list[dict], out_path: Path) -> list[dict]:
    """Каскад: tier1 (с сигналами) → опционально tier2 (только constraint)."""
    t1, t2 = build_tiers()
    out_f = out_path.open("w", encoding="utf-8")
    rows: list[dict] = []

    print(f"Router: {t1.model} @ {t1.base_url} → {t2.model} @ {t2.base_url}")
    print(f"Eval: {len(items)} (signals: constraint + scoring + selfcheck on tier1)")
    print(f"Escalate when: constraint=FAIL OR min_prob<{SCORING_ESCALATE_THRESHOLD} OR selfcheck.agree=false\n")

    try:
        for i, item in enumerate(items, 1):
            t0 = time.perf_counter()
            r1 = t1.predict(item["user"], with_signals=True)
            escalate, reason = decide_escalate(r1)

            r2 = None
            if escalate:
                r2 = t2.predict(item["user"], with_signals=False)
                final_parsed = r2["parsed"] or r1["parsed"]
                final_tier = "tier2"
            else:
                final_parsed = r1["parsed"]
                final_tier = "tier1"

            cat_m, sent_m = is_correct(final_parsed, item["expected"])
            wall_ms = (time.perf_counter() - t0) * 1000

            row = {
                "id": item["id"],
                "kind": item["kind"],
                "user_len": len(item["user"]),
                "expected": item["expected"],
                "tier1": {
                    "constraint_status": r1["constraint"]["status"],
                    "scoring_status": r1["scoring"]["status"] if r1["scoring"] else None,
                    "scoring_min_prob": r1["scoring"]["min_prob"] if r1["scoring"] else None,
                    "selfcheck_status": r1["selfcheck"]["status"] if r1["selfcheck"] else None,
                    "selfcheck_agree": (
                        r1["selfcheck"]["stage_b"].get("agree")
                        if r1["selfcheck"] and r1["selfcheck"].get("stage_b")
                        else None
                    ),
                    "parsed": r1["parsed"],
                    "latency_ms": r1["latency_ms"],
                    "tokens_in": r1["tokens_in"],
                    "tokens_out": r1["tokens_out"],
                },
                "tier2": (
                    {
                        "parsed": r2["parsed"],
                        "constraint_status": r2["constraint"]["status"],
                        "latency_ms": r2["latency_ms"],
                        "tokens_in": r2["tokens_in"],
                        "tokens_out": r2["tokens_out"],
                    }
                    if r2 else None
                ),
                "escalated": escalate,
                "escalate_reason": reason,
                "final_tier": final_tier,
                "final_parsed": final_parsed,
                "category_match": cat_m,
                "sentiment_match": sent_m,
                "joint_correct": cat_m and sent_m,
                "wall_latency_ms": round(wall_ms, 1),
                "total_tokens": r1["tokens_in"] + r1["tokens_out"] + (r2["tokens_in"] + r2["tokens_out"] if r2 else 0),
            }
            rows.append(row)
            out_f.write(json.dumps(row, ensure_ascii=False) + "\n")
            out_f.flush()

            mark = "✓" if cat_m and sent_m else "✗"
            tier_label = "T2" if escalate else "T1"
            print(
                f"[{i}/{len(items)}] {mark} {item['id']:<32} [{tier_label}] "
                f"reason={reason:<25} {row['wall_latency_ms']:.0f}ms"
            )
    finally:
        out_f.close()

    return rows


def run_baseline_tier2(items: list[dict], out_path: Path) -> list[dict]:
    """Baseline: всё на Tier 2 (только constraint, без сигналов)."""
    _, t2 = build_tiers()
    out_f = out_path.open("w", encoding="utf-8")
    rows: list[dict] = []

    print(f"Baseline (all-tier2): {t2.model} @ {t2.base_url}\n")
    try:
        for i, item in enumerate(items, 1):
            t0 = time.perf_counter()
            r = t2.predict(item["user"], with_signals=False)
            cat_m, sent_m = is_correct(r["parsed"], item["expected"])
            wall_ms = (time.perf_counter() - t0) * 1000
            row = {
                "id": item["id"],
                "kind": item["kind"],
                "expected": item["expected"],
                "parsed": r["parsed"],
                "category_match": cat_m,
                "sentiment_match": sent_m,
                "joint_correct": cat_m and sent_m,
                "wall_latency_ms": round(wall_ms, 1),
                "tokens_in": r["tokens_in"],
                "tokens_out": r["tokens_out"],
            }
            rows.append(row)
            out_f.write(json.dumps(row, ensure_ascii=False) + "\n")
            out_f.flush()
            mark = "✓" if cat_m and sent_m else "✗"
            print(f"[{i}/{len(items)}] {mark} {item['id']:<32} {wall_ms:.0f}ms")
    finally:
        out_f.close()
    return rows


# ---------- aggregation / report ----------

def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    k = (len(s) - 1) * q
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def aggregate(rows: list[dict]) -> dict:
    n = len(rows)
    correct = sum(1 for r in rows if r.get("joint_correct"))
    cat_ok = sum(1 for r in rows if r.get("category_match"))
    sent_ok = sum(1 for r in rows if r.get("sentiment_match"))
    lat = [r["wall_latency_ms"] for r in rows]
    tokens = sum(r.get("total_tokens", r.get("tokens_in", 0) + r.get("tokens_out", 0)) for r in rows)
    return {
        "n": n,
        "joint_acc": correct / n if n else 0,
        "cat_acc": cat_ok / n if n else 0,
        "sent_acc": sent_ok / n if n else 0,
        "avg_lat_ms": round(statistics.mean(lat), 1) if lat else 0,
        "p50_ms": round(percentile(lat, 0.5), 1),
        "p95_ms": round(percentile(lat, 0.95), 1),
        "tokens_total": tokens,
    }


def aggregate_router(rows: list[dict]) -> dict:
    base = aggregate(rows)
    n = len(rows)
    escalated = sum(1 for r in rows if r["escalated"])
    base["tier1_only"] = n - escalated
    base["escalated"] = escalated
    base["escalation_rate"] = escalated / n if n else 0
    base["escalation_reasons"] = dict(Counter(r["escalate_reason"] for r in rows))
    return base


def write_report(
    router_rows: list[dict],
    baseline_rows: list[dict] | None,
    report_path: Path,
):
    r_agg = aggregate_router(router_rows)
    b_agg = aggregate(baseline_rows) if baseline_rows else None

    md = [
        "# День 8 — Routing report\n",
        f"Total: **{r_agg['n']}** примеров (10 valid + 10 edge + 10 long).\n",
        "## Сводка router (tier1 → tier2 при сигналах дня 7)\n",
        "| метрика | router | baseline (all-tier2) |",
        "|---------|:------:|:--------------------:|",
        f"| joint accuracy | {r_agg['joint_acc']*100:.1f}% | {b_agg['joint_acc']*100:.1f}% |" if b_agg
        else f"| joint accuracy | {r_agg['joint_acc']*100:.1f}% | — |",
        f"| category accuracy | {r_agg['cat_acc']*100:.1f}% | {b_agg['cat_acc']*100:.1f}% |" if b_agg
        else f"| category accuracy | {r_agg['cat_acc']*100:.1f}% | — |",
        f"| sentiment accuracy | {r_agg['sent_acc']*100:.1f}% | {b_agg['sent_acc']*100:.1f}% |" if b_agg
        else f"| sentiment accuracy | {r_agg['sent_acc']*100:.1f}% | — |",
        f"| avg wall latency | {r_agg['avg_lat_ms']} ms | {b_agg['avg_lat_ms']} ms |" if b_agg
        else f"| avg wall latency | {r_agg['avg_lat_ms']} ms | — |",
        f"| p50 / p95 latency | {r_agg['p50_ms']} / {r_agg['p95_ms']} ms | {b_agg['p50_ms']} / {b_agg['p95_ms']} ms |" if b_agg
        else f"| p50 / p95 latency | {r_agg['p50_ms']} / {r_agg['p95_ms']} ms | — |",
        f"| total tokens | {r_agg['tokens_total']} | {b_agg['tokens_total']} |" if b_agg
        else f"| total tokens | {r_agg['tokens_total']} | — |",
        "",
        "## Маршрутизация",
        f"- Остался на Tier 1: **{r_agg['tier1_only']}/{r_agg['n']}** ({(1-r_agg['escalation_rate'])*100:.0f}%)",
        f"- Эскалировано на Tier 2: **{r_agg['escalated']}/{r_agg['n']}** ({r_agg['escalation_rate']*100:.0f}%)",
        "",
        "### Причины эскалации",
        "| reason | count |",
        "|--------|------:|",
    ]
    for reason, cnt in sorted(r_agg["escalation_reasons"].items(), key=lambda x: -x[1]):
        md.append(f"| `{reason}` | {cnt} |")

    # per-subset
    md.append("\n## По типам входа\n")
    md.append("| kind | n | joint_acc | tier1_only | escalated | avg_lat ms |")
    md.append("|------|---|-----------|------------|-----------|-----------:|")
    for kind in ["correct", "borderline", "noisy", "long"]:
        subset = [r for r in router_rows if r["kind"] == kind]
        if not subset:
            continue
        a = aggregate_router(subset)
        md.append(
            f"| {kind} | {a['n']} | {a['joint_acc']*100:.0f}% "
            f"| {a['tier1_only']} | {a['escalated']} | {a['avg_lat_ms']} |"
        )

    # per-row
    md.append("\n## Per-row\n")
    md.append("| id | kind | tier1 signals | escalated | reason | final | correct (cat/sent) |")
    md.append("|----|------|---------------|-----------|--------|-------|--------------------|")
    for r in router_rows:
        cm = "✓" if r["category_match"] else "✗"
        sm = "✓" if r["sentiment_match"] else "✗"
        sigs = (
            f"con={r['tier1']['constraint_status']}, "
            f"sc={r['tier1']['scoring_status']}({r['tier1']['scoring_min_prob']}), "
            f"slc={r['tier1']['selfcheck_status']}/agree={r['tier1']['selfcheck_agree']}"
        )
        md.append(
            f"| {r['id']} | {r['kind']} | {sigs} "
            f"| {'**T2**' if r['escalated'] else 'T1'} | `{r['escalate_reason']}` "
            f"| {r['final_tier']} | {cm}/{sm} |"
        )

    md.append(f"\nДетали per-row: `results/router_runs.jsonl`.")
    if baseline_rows is not None:
        md.append("Baseline per-row: `results/router_baseline.jsonl`.")

    report_path.write_text("\n".join(md), encoding="utf-8")
    print(f"\nReport: {report_path}")


# ---------- entrypoint ----------

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-n", "--num-correct", type=int, default=10)
    parser.add_argument("--baseline", action="store_true",
                        help="Прогнать только Tier 2 baseline (для сравнения accuracy)")
    parser.add_argument("--with-baseline", action="store_true",
                        help="Прогнать router + baseline в одной сессии")
    args = parser.parse_args()

    if not VALID.exists() or not META.exists():
        print("Нет valid.jsonl/split_meta.json — запусти build_dataset.py", file=sys.stderr)
        return 1
    if not EDGE.exists() or not LONG.exists():
        print("Нет edge_cases.jsonl/long_cases.jsonl", file=sys.stderr)
        return 1

    items = build_eval_set(args.num_correct)
    OUT.parent.mkdir(parents=True, exist_ok=True)

    if args.baseline:
        baseline_path = ROOT / "results" / "router_baseline.jsonl"
        rows = run_baseline_tier2(items, baseline_path)
        agg = aggregate(rows)
        print(f"\nBaseline acc={agg['joint_acc']*100:.1f}%  avg_lat={agg['avg_lat_ms']}ms  tokens={agg['tokens_total']}")
        return 0

    router_rows = run_router(items, OUT)
    baseline_rows = None
    if args.with_baseline:
        baseline_path = ROOT / "results" / "router_baseline.jsonl"
        baseline_rows = run_baseline_tier2(items, baseline_path)

    write_report(router_rows, baseline_rows, REPORT)
    agg = aggregate_router(router_rows)
    print(f"\nRouter acc={agg['joint_acc']*100:.1f}%  tier1_only={agg['tier1_only']}/{agg['n']}  "
          f"avg_lat={agg['avg_lat_ms']}ms  tokens={agg['tokens_total']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
