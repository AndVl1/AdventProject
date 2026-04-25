"""Baseline-замер на 10 примерах из valid.jsonl до FT.

Гоняет каждый пример через две модели:
- Tier 1 (FT-base): Qwen2.5-3B-Instruct Q4_K_M на TIER1_BASE_URL — это
  отправная точка "ДО fine-tuning"
- Tier 2 (teacher): Qwen3.6-35B-A3B на TIER2_BASE_URL — потолок без FT

Параметры:
- temperature=0
- GBNF grammar для гарантированного JSON-формата (через extra_body.grammar)
- enable_thinking=False для Qwen3-серии

Метрики per-row: parsed_ok, category_match, sentiment_match, latency_ms,
tokens. Итог — таблица в stdout + полный jsonl в results/baseline.jsonl.
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

from schema import GBNF_TICKET, build_messages

load_dotenv()

ROOT = Path(__file__).resolve().parent.parent
VALID = ROOT / "data" / "valid.jsonl"
META = ROOT / "data" / "split_meta.json"
OUT = ROOT / "results" / "baseline.jsonl"
SUMMARY = ROOT / "results" / "baseline-summary.md"


def load_eval(n: int) -> list[dict]:
    """Берём первые n примеров из valid.jsonl + matching meta для expected."""
    lines = [l for l in VALID.read_text(encoding="utf-8").splitlines() if l.strip()][:n]
    meta = json.loads(META.read_text(encoding="utf-8"))["valid"][:n]
    items = []
    for line, m in zip(lines, meta):
        obj = json.loads(line)
        msgs = obj["messages"]
        user = next(x for x in msgs if x["role"] == "user")["content"]
        items.append({
            "id": m["id"],
            "user": user,
            "expected": {"category": m["category"], "sentiment": m["sentiment"]},
        })
    return items


def call(client: OpenAI, model: str, user_text: str, *, use_grammar: bool, no_think: bool) -> tuple[str, dict]:
    msgs = build_messages(user_text)
    extra: dict = {}
    if use_grammar:
        extra["grammar"] = GBNF_TICKET
    if no_think:
        extra["chat_template_kwargs"] = {"enable_thinking": False}

    t0 = time.perf_counter()
    resp = client.chat.completions.create(
        model=model,
        messages=msgs,
        temperature=0,
        top_p=1,
        max_tokens=200,
        extra_body=extra or None,
    )
    dt = (time.perf_counter() - t0) * 1000
    msg = resp.choices[0].message
    content = (msg.content or "").strip()
    if not content:
        rc = getattr(msg, "reasoning_content", None) or ""
        content = rc.strip()
    # очистка <think> если просочился
    if "<think>" in content and "</think>" in content:
        content = content[content.rfind("</think>") + len("</think>"):].strip()

    usage = resp.usage
    return content, {
        "latency_ms": round(dt, 1),
        "tokens_in": getattr(usage, "prompt_tokens", None),
        "tokens_out": getattr(usage, "completion_tokens", None),
    }


def evaluate(answer: str, expected: dict) -> dict:
    parsed_ok = False
    cat_match = False
    sent_match = False
    parsed = None
    try:
        parsed = json.loads(answer)
        parsed_ok = (
            isinstance(parsed, dict)
            and "category" in parsed
            and "sentiment" in parsed
        )
        if parsed_ok:
            cat_match = parsed["category"] == expected["category"]
            sent_match = parsed["sentiment"] == expected["sentiment"]
    except Exception:
        pass
    return {
        "parsed_ok": parsed_ok,
        "category_match": cat_match,
        "sentiment_match": sent_match,
        "parsed": parsed,
    }


def aggregate(rows: list[dict], tier_key: str) -> dict:
    n = len(rows)
    parsed = sum(1 for r in rows if r[tier_key]["parsed_ok"])
    cat = sum(1 for r in rows if r[tier_key]["category_match"])
    sent = sum(1 for r in rows if r[tier_key]["sentiment_match"])
    lat = [r[tier_key]["meta"]["latency_ms"] for r in rows]
    tok_out = [r[tier_key]["meta"]["tokens_out"] or 0 for r in rows]
    return {
        "n": n,
        "parsed_ok": f"{parsed}/{n} ({100*parsed/n:.0f}%)",
        "category_acc": f"{cat}/{n} ({100*cat/n:.0f}%)",
        "sentiment_acc": f"{sent}/{n} ({100*sent/n:.0f}%)",
        "avg_latency_ms": f"{sum(lat)/n:.0f}",
        "avg_tokens_out": f"{sum(tok_out)/n:.1f}",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-n", "--num", type=int, default=10, help="кол-во примеров")
    parser.add_argument("--no-grammar", action="store_true", help="без GBNF (чистый промпт)")
    args = parser.parse_args()

    if not VALID.exists() or not META.exists():
        print("Сначала запусти build_dataset.py", file=sys.stderr)
        return 1

    items = load_eval(args.num)
    print(f"Eval: {len(items)} примеров из valid.jsonl\n")

    tier1_url = os.getenv("TIER1_BASE_URL", "http://127.0.0.1:8080/v1")
    tier1_model = os.getenv("TIER1_MODEL", "qwen2.5-3b")
    tier2_url = os.getenv("TIER2_BASE_URL", "http://127.0.0.1:8081/v1")
    tier2_model = os.getenv("TIER2_MODEL", "qwen3.6-35b-a3b")

    c1 = OpenAI(base_url=tier1_url, api_key="local", timeout=120)
    c2 = OpenAI(base_url=tier2_url, api_key="local", timeout=180)

    use_grammar = not args.no_grammar
    print(f"Tier 1 (base 3B): {tier1_model} @ {tier1_url}  grammar={use_grammar}")
    print(f"Tier 2 (teacher): {tier2_model} @ {tier2_url}  grammar={use_grammar}\n")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    out_f = OUT.open("w", encoding="utf-8")

    try:
        for i, item in enumerate(items, 1):
            print(f"[{i}/{len(items)}] {item['id']}  expected={item['expected']}")

            try:
                a1, m1 = call(c1, tier1_model, item["user"], use_grammar=use_grammar, no_think=False)
            except Exception as e:
                a1, m1 = "", {"latency_ms": 0, "tokens_in": None, "tokens_out": None, "error": str(e)}
                print(f"   tier1 ERROR: {e}")
            ev1 = evaluate(a1, item["expected"])

            try:
                a2, m2 = call(c2, tier2_model, item["user"], use_grammar=use_grammar, no_think=True)
            except Exception as e:
                a2, m2 = "", {"latency_ms": 0, "tokens_in": None, "tokens_out": None, "error": str(e)}
                print(f"   tier2 ERROR: {e}")
            ev2 = evaluate(a2, item["expected"])

            row = {
                "id": item["id"],
                "user": item["user"],
                "expected": item["expected"],
                "tier1_base_3b": {**ev1, "answer": a1, "meta": m1},
                "tier2_teacher": {**ev2, "answer": a2, "meta": m2},
            }
            rows.append(row)
            out_f.write(json.dumps(row, ensure_ascii=False) + "\n")
            out_f.flush()

            mark1 = "✓" if ev1["category_match"] and ev1["sentiment_match"] else "✗"
            mark2 = "✓" if ev2["category_match"] and ev2["sentiment_match"] else "✗"
            print(f"   tier1 {mark1} {a1[:60]!r}  ({m1['latency_ms']}ms)")
            print(f"   tier2 {mark2} {a2[:60]!r}  ({m2['latency_ms']}ms)\n")
    finally:
        out_f.close()

    s1 = aggregate(rows, "tier1_base_3b")
    s2 = aggregate(rows, "tier2_teacher")

    print("=" * 60)
    print(f"{'metric':<22} {'tier1 base 3B':<25} {'tier2 teacher':<25}")
    print("-" * 60)
    for key in ["parsed_ok", "category_acc", "sentiment_acc", "avg_latency_ms", "avg_tokens_out"]:
        print(f"{key:<22} {s1[key]:<25} {s2[key]:<25}")

    SUMMARY.parent.mkdir(parents=True, exist_ok=True)
    md = ["# Baseline (день 6)\n",
          f"Eval: {len(rows)} примеров. grammar={use_grammar}\n",
          "| metric | tier1 base 3B | tier2 teacher |",
          "|--------|---------------|---------------|"]
    for key in ["parsed_ok", "category_acc", "sentiment_acc", "avg_latency_ms", "avg_tokens_out"]:
        md.append(f"| {key} | {s1[key]} | {s2[key]} |")
    md.append("")
    md.append("Per-row: см. `results/baseline.jsonl`.")
    SUMMARY.write_text("\n".join(md), encoding="utf-8")
    print(f"\nДетали: {OUT}")
    print(f"Сводка: {SUMMARY}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
