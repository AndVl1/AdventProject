"""День 7 — confidence layer на logprobs.

Замер уверенности классификатора per-row: запрашиваем logprobs у llama-server,
вытаскиваем токены значений `category` и `sentiment`, считаем joint-prob.

Вход: data/valid.jsonl + data/split_meta.json (как в baseline.py).
Выход: results/confidence.jsonl + results/confidence-summary.md.

Метрики:
- token_logprob (per chosen token)
- cat_prob = exp(sum logprob токенов значения category)
- sent_prob = аналогично для sentiment
- joint_prob = cat_prob * sent_prob
- min_prob = min(cat_prob, sent_prob) — самое слабое звено

Калибровка: bucketed precision (5 бакетов по min_prob), ECE.

Использование:
    python scripts/confidence.py --tier 1 -n 14
    python scripts/confidence.py --tier 1 -n 14 --no-grammar  # без GBNF
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dotenv import load_dotenv
from openai import OpenAI

from schema import GBNF_TICKET, build_messages

load_dotenv()

ROOT = Path(__file__).resolve().parent.parent
VALID = ROOT / "data" / "valid.jsonl"
META = ROOT / "data" / "split_meta.json"
OUT = ROOT / "results" / "confidence.jsonl"
SUMMARY = ROOT / "results" / "confidence-summary.md"


def load_eval(n: int) -> list[dict]:
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


def call_with_logprobs(
    client: OpenAI,
    model: str,
    user_text: str,
    *,
    use_grammar: bool,
    no_think: bool,
) -> tuple[str, list[dict], dict]:
    """Возвращает (content, per_token_logprobs, meta)."""
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
        logprobs=True,
        top_logprobs=5,
        extra_body=extra or None,
    )
    dt = (time.perf_counter() - t0) * 1000
    msg = resp.choices[0].message
    content = (msg.content or "").strip()
    if not content:
        rc = getattr(msg, "reasoning_content", None) or ""
        content = rc.strip()
    if "<think>" in content and "</think>" in content:
        content = content[content.rfind("</think>") + len("</think>"):].strip()

    lp = resp.choices[0].logprobs
    tokens: list[dict] = []
    if lp and getattr(lp, "content", None):
        for tok in lp.content:
            tokens.append({
                "token": tok.token,
                "logprob": tok.logprob,
            })

    usage = resp.usage
    return content, tokens, {
        "latency_ms": round(dt, 1),
        "tokens_in": getattr(usage, "prompt_tokens", None),
        "tokens_out": getattr(usage, "completion_tokens", None),
    }


def find_value_tokens(tokens: list[dict], key: str, value: str) -> list[dict]:
    """Находим в потоке токенов те, что покрывают значение `value` ключа `key`.

    Робастно: реконструируем полный текст из последовательности токенов
    (запоминаем char-диапазоны), потом regex-ом находим
    `"<key>"\\s*:\\s*"<value>"` и берём токены, чьи диапазоны пересекают
    позицию value-substring.
    """
    if not tokens or not value:
        return []

    spans: list[tuple[int, int]] = []
    pos = 0
    full = []
    for t in tokens:
        s = t["token"]
        spans.append((pos, pos + len(s)))
        full.append(s)
        pos += len(s)
    text = "".join(full)

    pattern = re.compile(
        r'"' + re.escape(key) + r'"\s*:\s*"(' + re.escape(value) + r')"'
    )
    m = pattern.search(text)
    if not m:
        return []
    v_start, v_end = m.start(1), m.end(1)

    result: list[dict] = []
    for tok, (a, b) in zip(tokens, spans):
        if b <= v_start or a >= v_end:
            continue
        result.append(tok)
    return result


def joint_prob(token_lps: list[dict]) -> float:
    if not token_lps:
        return 0.0
    return math.exp(sum(t["logprob"] for t in token_lps))


def evaluate(answer: str, expected: dict) -> dict:
    parsed_ok = False
    cat_match = False
    sent_match = False
    parsed = None
    try:
        parsed = json.loads(answer)
        parsed_ok = isinstance(parsed, dict) and "category" in parsed and "sentiment" in parsed
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


def calibration_table(rows: list[dict], field: str) -> list[dict]:
    """Бакетируем по prob_field, считаем precision в каждом бакете."""
    buckets = [(0.0, 0.5), (0.5, 0.7), (0.7, 0.85), (0.85, 0.95), (0.95, 1.001)]
    table: list[dict] = []
    prob_key = f"{field}_prob"
    match_key = f"{field}_match"
    for lo, hi in buckets:
        items = [r for r in rows if lo <= r["confidence"][prob_key] < hi]
        if not items:
            table.append({"bucket": f"[{lo:.2f}, {hi:.2f})", "n": 0, "precision": None, "avg_prob": None})
            continue
        correct = sum(1 for r in items if r["eval"][match_key])
        avg = sum(r["confidence"][prob_key] for r in items) / len(items)
        table.append({
            "bucket": f"[{lo:.2f}, {hi:.2f})",
            "n": len(items),
            "precision": correct / len(items),
            "avg_prob": avg,
        })
    return table


def ece(rows: list[dict], field: str) -> float:
    """Expected Calibration Error: weighted |confidence - accuracy| по бакетам."""
    table = calibration_table(rows, field)
    n_total = sum(b["n"] for b in table)
    if n_total == 0:
        return 0.0
    err = 0.0
    for b in table:
        if b["n"] == 0:
            continue
        err += (b["n"] / n_total) * abs(b["avg_prob"] - b["precision"])
    return err


def suggest_threshold(rows: list[dict], field: str, target_precision: float) -> float | None:
    """Минимальный prob threshold при котором precision >= target."""
    prob_key = f"{field}_prob"
    match_key = f"{field}_match"
    candidates = sorted({round(r["confidence"][prob_key], 3) for r in rows})
    for thr in candidates:
        kept = [r for r in rows if r["confidence"][prob_key] >= thr]
        if not kept:
            continue
        prec = sum(1 for r in kept if r["eval"][match_key]) / len(kept)
        if prec >= target_precision:
            return thr
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-n", "--num", type=int, default=14)
    parser.add_argument("--tier", type=int, default=1, choices=[1, 2])
    parser.add_argument("--no-grammar", action="store_true")
    args = parser.parse_args()

    if not VALID.exists() or not META.exists():
        print("Нет valid.jsonl/split_meta.json — запусти build_dataset.py", file=sys.stderr)
        return 1

    if args.tier == 1:
        url = os.getenv("TIER1_BASE_URL", "http://127.0.0.1:8080/v1")
        model = os.getenv("TIER1_MODEL", "qwen2.5-3b")
        no_think = False
    else:
        url = os.getenv("TIER2_BASE_URL", "http://127.0.0.1:8081/v1")
        model = os.getenv("TIER2_MODEL", "qwen3.6-35b-a3b")
        no_think = True

    client = OpenAI(base_url=url, api_key="local", timeout=180)
    use_grammar = not args.no_grammar

    items = load_eval(args.num)
    print(f"Tier {args.tier}: {model} @ {url}  grammar={use_grammar}")
    print(f"Eval: {len(items)} примеров\n")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    out_f = OUT.open("w", encoding="utf-8")

    try:
        for i, item in enumerate(items, 1):
            try:
                ans, tokens, meta = call_with_logprobs(
                    client, model, item["user"],
                    use_grammar=use_grammar, no_think=no_think,
                )
            except Exception as e:
                print(f"[{i}] ERROR: {e}")
                continue

            ev = evaluate(ans, item["expected"])
            parsed = ev["parsed"] or {}

            cat_value = parsed.get("category", "")
            sent_value = parsed.get("sentiment", "")
            cat_toks = find_value_tokens(tokens, "category", cat_value) if cat_value else []
            sent_toks = find_value_tokens(tokens, "sentiment", sent_value) if sent_value else []

            cat_p = joint_prob(cat_toks)
            sent_p = joint_prob(sent_toks)
            joint = cat_p * sent_p
            min_p = min(cat_p, sent_p) if (cat_toks and sent_toks) else 0.0

            row = {
                "id": item["id"],
                "user": item["user"],
                "expected": item["expected"],
                "answer": ans,
                "eval": {
                    "parsed_ok": ev["parsed_ok"],
                    "category_match": ev["category_match"],
                    "sentiment_match": ev["sentiment_match"],
                },
                "confidence": {
                    "category_prob": round(cat_p, 4),
                    "sentiment_prob": round(sent_p, 4),
                    "joint_prob": round(joint, 4),
                    "min_prob": round(min_p, 4),
                    "n_cat_tokens": len(cat_toks),
                    "n_sent_tokens": len(sent_toks),
                    "total_tokens": len(tokens),
                },
                "meta": meta,
            }
            rows.append(row)
            out_f.write(json.dumps(row, ensure_ascii=False) + "\n")
            out_f.flush()

            mark = "✓" if ev["category_match"] and ev["sentiment_match"] else "✗"
            print(
                f"[{i}/{len(items)}] {mark} {item['id']}  "
                f"cat={cat_value!r} p={cat_p:.3f}  sent={sent_value!r} p={sent_p:.3f}  "
                f"min={min_p:.3f}"
            )
    finally:
        out_f.close()

    if not rows:
        print("Нет результатов", file=sys.stderr)
        return 1

    cat_table = calibration_table(rows, "category")
    sent_table = calibration_table(rows, "sentiment")
    ece_cat = ece(rows, "category")
    ece_sent = ece(rows, "sentiment")
    thr_cat_85 = suggest_threshold(rows, "category", 0.85)
    thr_sent_85 = suggest_threshold(rows, "sentiment", 0.85)

    print("\n=== Calibration: category ===")
    for b in cat_table:
        if b["n"] == 0:
            print(f"  {b['bucket']:<14} n=0")
        else:
            print(f"  {b['bucket']:<14} n={b['n']:<3} precision={b['precision']:.2f} avg_prob={b['avg_prob']:.3f}")
    print(f"  ECE = {ece_cat:.3f}")
    print(f"  threshold for precision>=0.85: {thr_cat_85}")

    print("\n=== Calibration: sentiment ===")
    for b in sent_table:
        if b["n"] == 0:
            print(f"  {b['bucket']:<14} n=0")
        else:
            print(f"  {b['bucket']:<14} n={b['n']:<3} precision={b['precision']:.2f} avg_prob={b['avg_prob']:.3f}")
    print(f"  ECE = {ece_sent:.3f}")
    print(f"  threshold for precision>=0.85: {thr_sent_85}")

    SUMMARY.parent.mkdir(parents=True, exist_ok=True)
    md: list[str] = [
        f"# Confidence (день 7) — tier {args.tier}\n",
        f"Eval: {len(rows)} примеров. grammar={use_grammar}. model={model}\n",
        "## Метод",
        "logprobs=True, top_logprobs=5. Per-field prob = exp(sum logprob токенов значения).",
        "min_prob = min(cat_prob, sent_prob) — главный индикатор для маршрутизатора.\n",
        "## Калибровка category",
        "| bucket | n | precision | avg_prob |",
        "|--------|---|-----------|----------|",
    ]
    for b in cat_table:
        if b["n"] == 0:
            md.append(f"| {b['bucket']} | 0 | — | — |")
        else:
            md.append(f"| {b['bucket']} | {b['n']} | {b['precision']:.2f} | {b['avg_prob']:.3f} |")
    md.append(f"\nECE = **{ece_cat:.3f}**")
    md.append(f"\nThreshold для precision ≥ 0.85: **{thr_cat_85}**\n")

    md.append("## Калибровка sentiment")
    md.append("| bucket | n | precision | avg_prob |")
    md.append("|--------|---|-----------|----------|")
    for b in sent_table:
        if b["n"] == 0:
            md.append(f"| {b['bucket']} | 0 | — | — |")
        else:
            md.append(f"| {b['bucket']} | {b['n']} | {b['precision']:.2f} | {b['avg_prob']:.3f} |")
    md.append(f"\nECE = **{ece_sent:.3f}**")
    md.append(f"\nThreshold для precision ≥ 0.85: **{thr_sent_85}**\n")

    md.append("## Использование маршрутизатором")
    md.append(
        "При `min_prob < threshold` → метка считается uncertain, эскалируем на tier 2 "
        "(день 8: routing). Threshold подбирается из таблицы выше под целевой precision."
    )
    md.append("\nПер-row: см. `results/confidence.jsonl`.")

    SUMMARY.write_text("\n".join(md), encoding="utf-8")
    print(f"\nДетали: {OUT}")
    print(f"Сводка: {SUMMARY}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
