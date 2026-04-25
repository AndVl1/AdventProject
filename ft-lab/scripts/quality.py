"""День 7 — Quality orchestrator.

Гонит 20 примеров (10 valid + 10 edge_cases) через три метода:
- constraint    : без grammar + retry + fallback на GBNF
- scoring       : logprobs из confidence.py (use_grammar=True)
- selfcheck     : двухэтапная верификация

Сводный статус:
  OK     — constraint=OK И scoring=OK И selfcheck=OK
  FAIL   — constraint=FAIL
  UNSURE — иначе

Метрики:
- per-row: status каждого метода + final, корректность (vs expected),
           latency_ms, tokens_in/out, attempts (constraint), retried.
- per-method: counts OK/UNSURE/FAIL, accuracy (среди OK), avg latency.
- сводка: cost (total tokens), avg full-pipeline latency,
          сколько потребовало ретрая, сколько отклонено.

Выход: results/quality.jsonl + results/day7-report.md.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections import Counter
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
OUT = ROOT / "results" / "quality.jsonl"
REPORT = ROOT / "results" / "day7-report.md"


def load_correct(n: int) -> list[dict]:
    """Первые n из valid.jsonl + matching meta."""
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


def load_edge() -> list[dict]:
    items = []
    for line in EDGE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            items.append(json.loads(line))
    return items


def is_correct(parsed: dict | None, expected: dict) -> tuple[bool, bool]:
    if not parsed or not isinstance(parsed, dict):
        return False, False
    cat = parsed.get("category") == expected["category"]
    sent = parsed.get("sentiment") == expected["sentiment"]
    return cat, sent


def aggregate_method(rows: list[dict], method: str) -> dict:
    statuses = Counter(r[method]["status"] for r in rows)
    correct = sum(1 for r in rows if r[method].get("category_match") and r[method].get("sentiment_match"))
    cat_corr = sum(1 for r in rows if r[method].get("category_match"))
    sent_corr = sum(1 for r in rows if r[method].get("sentiment_match"))
    lat = [r[method]["latency_ms"] for r in rows]
    tokens_in = sum(r[method]["tokens_in"] for r in rows)
    tokens_out = sum(r[method]["tokens_out"] for r in rows)
    n = len(rows)
    return {
        "n": n,
        "OK": statuses.get("OK", 0),
        "UNSURE": statuses.get("UNSURE", 0),
        "FAIL": statuses.get("FAIL", 0),
        "joint_correct": correct,
        "joint_acc": f"{correct}/{n} ({100*correct/n:.0f}%)",
        "category_acc": f"{cat_corr}/{n} ({100*cat_corr/n:.0f}%)",
        "sentiment_acc": f"{sent_corr}/{n} ({100*sent_corr/n:.0f}%)",
        "avg_latency_ms": round(sum(lat) / n, 1),
        "tokens_in": tokens_in,
        "tokens_out": tokens_out,
    }


def aggregate_subset(rows: list[dict], kind: str | None) -> list[dict]:
    subset = rows if kind is None else [r for r in rows if r["kind"] == kind]
    if not subset:
        return []
    methods = ["constraint", "scoring", "selfcheck", "final"]
    return [(m, aggregate_method(subset, m)) for m in methods]


def overall_summary(rows: list[dict]) -> dict:
    n = len(rows)
    retried = sum(1 for r in rows if r["constraint"].get("retried"))
    fallback = sum(1 for r in rows if r["constraint"].get("fallback_grammar"))
    rejected = sum(1 for r in rows if r["final"]["status"] != "OK")
    pipeline_lat = [r["pipeline_latency_ms"] for r in rows]
    pipeline_tok = sum(r["pipeline_tokens_in"] + r["pipeline_tokens_out"] for r in rows)
    return {
        "n": n,
        "retried_constraint": retried,
        "fallback_grammar": fallback,
        "rejected_final": rejected,
        "pipeline_avg_latency_ms": round(sum(pipeline_lat) / n, 1),
        "pipeline_total_tokens": pipeline_tok,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-n", "--num-correct", type=int, default=10)
    parser.add_argument("--tier", type=int, default=1, choices=[1, 2])
    args = parser.parse_args()

    if args.tier == 1:
        url = os.getenv("TIER1_BASE_URL", "http://127.0.0.1:8080/v1")
        model = os.getenv("TIER1_MODEL", "qwen2.5-3b")
        no_think = False
    else:
        url = os.getenv("TIER2_BASE_URL", "http://127.0.0.1:8081/v1")
        model = os.getenv("TIER2_MODEL", "qwen3.6-35b-a3b")
        no_think = True

    client = OpenAI(base_url=url, api_key="local", timeout=240)

    items = load_correct(args.num_correct) + load_edge()
    print(f"Tier {args.tier}: {model} @ {url}")
    print(f"Total: {len(items)} (correct={args.num_correct}, edge={len(items)-args.num_correct})\n")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    out_f = OUT.open("w", encoding="utf-8")
    rows: list[dict] = []

    try:
        for i, item in enumerate(items, 1):
            t0 = time.perf_counter()
            print(f"[{i}/{len(items)}] {item['id']} ({item['kind']}) expected={item['expected']}")

            # 1. constraint
            con = constraint_check(client, model, item["user"], no_think=no_think)
            cat_m, sent_m = is_correct(con.get("parsed"), item["expected"])
            con_row = {
                "status": con["status"],
                "category_match": cat_m,
                "sentiment_match": sent_m,
                "attempts": con["attempts"],
                "retried": con["retried"],
                "fallback_grammar": con["fallback_grammar"],
                "answer": con["answer"],
                "parsed": con.get("parsed"),
                "error": con.get("error"),
                "latency_ms": con["latency_ms"],
                "tokens_in": con["tokens_in"],
                "tokens_out": con["tokens_out"],
            }
            print(f"  constraint  : {con['status']:<6} attempts={con['attempts']} cat={cat_m} sent={sent_m} ({con['latency_ms']}ms)")

            # 2. scoring (logprobs)
            sc = score_logprobs(client, model, item["user"], use_grammar=True, no_think=no_think)
            cat_m2, sent_m2 = is_correct(sc.get("parsed"), item["expected"])
            sc_row = {
                "status": sc["status"],
                "category_match": cat_m2,
                "sentiment_match": sent_m2,
                "answer": sc["answer"],
                "parsed": sc.get("parsed"),
                "category_prob": sc["category_prob"],
                "sentiment_prob": sc["sentiment_prob"],
                "min_prob": sc["min_prob"],
                "latency_ms": sc["latency_ms"],
                "tokens_in": sc["tokens_in"],
                "tokens_out": sc["tokens_out"],
            }
            print(f"  scoring     : {sc['status']:<6} min_prob={sc['min_prob']} cat={cat_m2} sent={sent_m2} ({sc['latency_ms']}ms)")

            # 3. selfcheck
            slc = selfcheck(client, model, item["user"], no_think=no_think)
            cat_m3, sent_m3 = is_correct(slc.get("final"), item["expected"])
            slc_row = {
                "status": slc["status"],
                "category_match": cat_m3,
                "sentiment_match": sent_m3,
                "stage_a": slc.get("stage_a"),
                "stage_b": slc.get("stage_b"),
                "final": slc.get("final"),
                "latency_ms": slc["latency_ms"],
                "tokens_in": slc["tokens_in"],
                "tokens_out": slc["tokens_out"],
            }
            print(f"  selfcheck   : {slc['status']:<6} cat={cat_m3} sent={sent_m3} ({slc['latency_ms']}ms)")

            # final
            if con["status"] == "FAIL":
                final_status = "FAIL"
            elif con["status"] == "OK" and sc["status"] == "OK" and slc["status"] == "OK":
                final_status = "OK"
            else:
                final_status = "UNSURE"

            # final answer выбираем приоритетом: selfcheck.final > scoring.parsed > constraint.parsed
            final_parsed = (
                slc.get("final")
                or (sc.get("parsed") if isinstance(sc.get("parsed"), dict) else None)
                or con.get("parsed")
            )
            cat_mf, sent_mf = is_correct(final_parsed, item["expected"])
            final_row = {
                "status": final_status,
                "parsed": final_parsed,
                "category_match": cat_mf,
                "sentiment_match": sent_mf,
                # latency и токены — синтетические, берём суммарно по пайплайну (см. ниже)
                "latency_ms": 0.0,
                "tokens_in": 0,
                "tokens_out": 0,
            }

            pipeline_lat = (time.perf_counter() - t0) * 1000
            row = {
                "id": item["id"],
                "kind": item["kind"],
                "user": item["user"],
                "expected": item["expected"],
                "constraint": con_row,
                "scoring": sc_row,
                "selfcheck": slc_row,
                "final": final_row,
                "pipeline_latency_ms": round(pipeline_lat, 1),
                "pipeline_tokens_in": con["tokens_in"] + sc["tokens_in"] + slc["tokens_in"],
                "pipeline_tokens_out": con["tokens_out"] + sc["tokens_out"] + slc["tokens_out"],
            }
            # подставим финальные latency/tokens равными суммарным — для агрегации
            row["final"]["latency_ms"] = row["pipeline_latency_ms"]
            row["final"]["tokens_in"] = row["pipeline_tokens_in"]
            row["final"]["tokens_out"] = row["pipeline_tokens_out"]

            rows.append(row)
            out_f.write(json.dumps(row, ensure_ascii=False) + "\n")
            out_f.flush()
            print(f"  >>> FINAL   : {final_status:<6} cat={cat_mf} sent={sent_mf} pipeline={row['pipeline_latency_ms']:.0f}ms\n")
    finally:
        out_f.close()

    if not rows:
        print("Нет результатов", file=sys.stderr)
        return 1

    overall = overall_summary(rows)

    md = [
        f"# День 7 — Quality report (tier {args.tier})\n",
        f"Model: `{model}`. Total: **{overall['n']}** "
        f"(correct={args.num_correct}, edge={overall['n']-args.num_correct}).\n",
        "## Сводка пайплайна",
        f"- Отклонено финально (status != OK): **{overall['rejected_final']}/{overall['n']}**",
        f"- Потребовало ретрая (constraint): **{overall['retried_constraint']}**",
        f"- Сработал fallback на GBNF: **{overall['fallback_grammar']}**",
        f"- Avg latency полного пайплайна: **{overall['pipeline_avg_latency_ms']} ms**",
        f"- Total tokens (in+out): **{overall['pipeline_total_tokens']}**\n",
    ]

    sections = [
        ("Все 20", None),
        ("Correct (10 valid)", "correct"),
        ("Borderline (5)", "borderline"),
        ("Noisy (5)", "noisy"),
    ]
    for title, kind in sections:
        agg = aggregate_subset(rows, kind)
        if not agg:
            continue
        md.append(f"## {title}\n")
        md.append("| method | n | OK | UNSURE | FAIL | joint_acc | category_acc | sentiment_acc | avg_lat ms | tokens (in+out) |")
        md.append("|--------|---|----|--------|------|-----------|--------------|---------------|------------|-----------------|")
        for method, a in agg:
            md.append(
                f"| {method} | {a['n']} | {a['OK']} | {a['UNSURE']} | {a['FAIL']} "
                f"| {a['joint_acc']} | {a['category_acc']} | {a['sentiment_acc']} "
                f"| {a['avg_latency_ms']} | {a['tokens_in']+a['tokens_out']} |"
            )
        md.append("")

    md.append("## Per-row")
    md.append("| id | kind | constraint | scoring (min_p) | selfcheck | final | correct (cat/sent) |")
    md.append("|----|------|------------|------------------|-----------|-------|-----|")
    for r in rows:
        cmark = "✓" if r["final"]["category_match"] else "✗"
        smark = "✓" if r["final"]["sentiment_match"] else "✗"
        md.append(
            f"| {r['id']} | {r['kind']} | {r['constraint']['status']} (a={r['constraint']['attempts']}) "
            f"| {r['scoring']['status']} ({r['scoring']['min_prob']}) "
            f"| {r['selfcheck']['status']} | **{r['final']['status']}** | {cmark}/{smark} |"
        )
    md.append("\nДетали per-row: `results/quality.jsonl`.")

    REPORT.write_text("\n".join(md), encoding="utf-8")
    print("=" * 60)
    print(f"Sections written. Report: {REPORT}")
    print(f"Per-row: {OUT}")
    print(f"Pipeline avg latency: {overall['pipeline_avg_latency_ms']} ms")
    print(f"Rejected (status != OK): {overall['rejected_final']}/{overall['n']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
