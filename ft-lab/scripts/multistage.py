"""День 9 — Multi-stage inference.

Расширенная схема извлечения: {category, sentiment, severity, affected_component, suggested_action}.

Два режима для сравнения:
- mono   : один промпт + GBNF на полную схему. Один вызов LLM.
- multi  : 3 этапа.
    Этап 1 — нормализация (T1+GBNF) → lang, summary, keywords, has_error_msg.
    Этап 2 — 3 enum-вызова (T1+GBNF) → category, sentiment, severity.
    Этап 3 — извлечение affected_component + suggested_action.
              Если severity=critical OR category=billing → принудительно T2 (логика дня 8 переиспользуется).
              Иначе T1.

Прогон по `data/eval/day9_eval.jsonl` (20 примеров, расширенная разметка).

CLI:
    python scripts/multistage.py --mode mono
    python scripts/multistage.py --mode multi
    python scripts/multistage.py --mode both         # дефолт — оба и сравнительный отчёт
    python scripts/multistage.py --tag custom        # суффикс отчётов

Env:
    TIER1_BASE_URL, TIER1_MODEL  (default 8080, qwen2.5-3b)
    TIER2_BASE_URL, TIER2_MODEL  (default 8081, qwen3.6-35b-a3b)
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dotenv import load_dotenv
from openai import OpenAI

load_dotenv()

ROOT = Path(__file__).resolve().parent.parent
EVAL_FILE = ROOT / "data" / "eval" / "day9_eval.jsonl"
RESULTS = ROOT / "results"

# ---------- schema ----------

CATEGORIES = ("bug", "feature_request", "billing", "how_to", "other")
SENTIMENTS = ("neg", "neu", "pos")
SEVERITIES = ("low", "medium", "high", "critical")
ACTIONS = ("escalate_eng", "reply_template", "request_more_info", "close")


def _enum_alt(values: tuple[str, ...]) -> str:
    return " | ".join(f'"\\"{v}\\""' for v in values)


GBNF_FULL = (
    "root ::= \"{\" ws "
    "\"\\\"category\\\"\" ws \":\" ws cat ws \",\" ws "
    "\"\\\"sentiment\\\"\" ws \":\" ws sent ws \",\" ws "
    "\"\\\"severity\\\"\" ws \":\" ws sev ws \",\" ws "
    "\"\\\"affected_component\\\"\" ws \":\" ws str40 ws \",\" ws "
    "\"\\\"suggested_action\\\"\" ws \":\" ws act ws \"}\"\n"
    f"cat  ::= {_enum_alt(CATEGORIES)}\n"
    f"sent ::= {_enum_alt(SENTIMENTS)}\n"
    f"sev  ::= {_enum_alt(SEVERITIES)}\n"
    f"act  ::= {_enum_alt(ACTIONS)}\n"
    "str40 ::= \"\\\"\" char{1,40} \"\\\"\"\n"
    "char  ::= [^\\\"\\\\\\n\\r] | \"\\\\\\\"\"\n"
    "ws    ::= [ \\t\\n]*\n"
)

GBNF_NORMALIZE = (
    "root ::= \"{\" ws "
    "\"\\\"lang\\\"\" ws \":\" ws lang ws \",\" ws "
    "\"\\\"summary\\\"\" ws \":\" ws str120 ws \",\" ws "
    "\"\\\"keywords\\\"\" ws \":\" ws kwlist ws \",\" ws "
    "\"\\\"has_error_msg\\\"\" ws \":\" ws bool ws \"}\"\n"
    "lang  ::= \"\\\"ru\\\"\" | \"\\\"en\\\"\" | \"\\\"mixed\\\"\" | \"\\\"other\\\"\"\n"
    "str120 ::= \"\\\"\" char{1,120} \"\\\"\"\n"
    "kwlist ::= \"[\" ws str40 (ws \",\" ws str40){0,4} ws \"]\"\n"
    "str40 ::= \"\\\"\" char{1,40} \"\\\"\"\n"
    "char  ::= [^\\\"\\\\\\n\\r] | \"\\\\\\\"\"\n"
    "bool  ::= \"true\" | \"false\"\n"
    "ws    ::= [ \\t\\n]*\n"
)

GBNF_ENUM_CATEGORY = (
    "root ::= \"{\" ws \"\\\"category\\\"\" ws \":\" ws cat ws \"}\"\n"
    f"cat  ::= {_enum_alt(CATEGORIES)}\n"
    "ws   ::= [ \\t\\n]*\n"
)
GBNF_ENUM_SENTIMENT = (
    "root ::= \"{\" ws \"\\\"sentiment\\\"\" ws \":\" ws sent ws \"}\"\n"
    f"sent ::= {_enum_alt(SENTIMENTS)}\n"
    "ws   ::= [ \\t\\n]*\n"
)
GBNF_ENUM_SEVERITY = (
    "root ::= \"{\" ws \"\\\"severity\\\"\" ws \":\" ws sev ws \"}\"\n"
    f"sev  ::= {_enum_alt(SEVERITIES)}\n"
    "ws   ::= [ \\t\\n]*\n"
)

GBNF_COMPONENT_ACTION = (
    "root ::= \"{\" ws "
    "\"\\\"affected_component\\\"\" ws \":\" ws str40 ws \",\" ws "
    "\"\\\"suggested_action\\\"\" ws \":\" ws act ws \"}\"\n"
    f"act ::= {_enum_alt(ACTIONS)}\n"
    "str40 ::= \"\\\"\" char{1,40} \"\\\"\"\n"
    "char  ::= [^\\\"\\\\\\n\\r] | \"\\\\\\\"\"\n"
    "ws ::= [ \\t\\n]*\n"
)

# ---------- prompts ----------

SYS_MONO = (
    "Ты обрабатываешь тикет поддержки. Верни СТРОГО один JSON со всеми полями: "
    "{\"category\": one of [bug, feature_request, billing, how_to, other], "
    "\"sentiment\": one of [neg, neu, pos], "
    "\"severity\": one of [low, medium, high, critical], "
    "\"affected_component\": краткое описание затронутого компонента (≤40 символов, английский), "
    "\"suggested_action\": one of [escalate_eng, reply_template, request_more_info, close]}. "
    "Без обрамляющего текста."
)

SYS_NORMALIZE = (
    "Извлеки из тикета поддержки структурированную сводку. Верни СТРОГО JSON: "
    "{\"lang\": one of [ru, en, mixed, other], "
    "\"summary\": кратко суть проблемы одним предложением (≤120 символов), "
    "\"keywords\": массив 1-5 ключевых слов на английском (каждое ≤40 символов), "
    "\"has_error_msg\": true если в тикете есть текст ошибки/stack-trace/код ошибки}. Без обрамления."
)

SYS_CATEGORY = (
    "Определи категорию тикета. Верни JSON: {\"category\": one of [bug, feature_request, billing, how_to, other]}. "
    "bug — баг/поломка/сбой; feature_request — просьба добавить функцию; "
    "billing — деньги/платёж/счёт/тариф; how_to — вопрос как сделать; other — благодарность/отзыв/мелочь. "
    "Без лишнего текста."
)
SYS_SENTIMENT = (
    "Определи тональность тикета. Верни JSON: {\"sentiment\": one of [neg, neu, pos]}. "
    "neg — негатив/жалоба/угроза; pos — благодарность/похвала; neu — нейтрально/деловой запрос. Без лишнего текста."
)
SYS_SEVERITY = (
    "Определи серьёзность тикета. Верни JSON: {\"severity\": one of [low, medium, high, critical]}. "
    "critical — блокирует production / много пользователей / деньги уже потеряны. "
    "high — блокирует команду / нет workaround. "
    "medium — заметное неудобство, есть workaround. "
    "low — мелкое / косметика / просьба. Без лишнего текста."
)

SYS_COMPONENT_ACTION = (
    "Извлеки из тикета affected_component и предложи действие. Верни СТРОГО JSON: "
    "{\"affected_component\": краткое описание компонента/области (≤40 символов, английский), "
    "\"suggested_action\": one of [escalate_eng, reply_template, request_more_info, close]}. "
    "escalate_eng — баг/critical billing/требует разработчика; "
    "reply_template — стандартный ответ-шаблон (how_to, типовое feature_request); "
    "request_more_info — мало данных для решения; "
    "close — благодарность/нейтральный отзыв без действия. Без обрамления."
)

# ---------- llama.cpp client ----------


def _strip_think(content: str) -> str:
    if "<think>" in content and "</think>" in content:
        return content[content.rfind("</think>") + len("</think>"):].strip()
    return content


def _call(
    client: OpenAI,
    model: str,
    system: str,
    user: str,
    *,
    grammar: str | None,
    no_think: bool,
    max_tokens: int = 200,
) -> tuple[str, dict]:
    extra: dict = {}
    if grammar:
        extra["grammar"] = grammar
    if no_think:
        extra["chat_template_kwargs"] = {"enable_thinking": False}
    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    ]
    t0 = time.perf_counter()
    resp = client.chat.completions.create(
        model=model,
        messages=messages,
        temperature=0,
        top_p=1,
        max_tokens=max_tokens,
        extra_body=extra or None,
    )
    dt = (time.perf_counter() - t0) * 1000
    msg = resp.choices[0].message
    content = (msg.content or "").strip()
    if not content:
        rc = getattr(msg, "reasoning_content", None) or ""
        content = rc.strip()
    content = _strip_think(content)
    usage = resp.usage
    return content, {
        "latency_ms": round(dt, 1),
        "tokens_in": getattr(usage, "prompt_tokens", None) or 0,
        "tokens_out": getattr(usage, "completion_tokens", None) or 0,
    }


def _safe_json(text: str) -> dict | None:
    try:
        obj = json.loads(text)
        return obj if isinstance(obj, dict) else None
    except Exception:
        return None


# ---------- tiers ----------


@dataclass
class Tier:
    name: str
    base_url: str
    model: str
    no_think: bool = True
    client: OpenAI = field(init=False)

    def __post_init__(self):
        self.client = OpenAI(base_url=self.base_url, api_key="local", timeout=240)


def build_tiers() -> tuple[Tier, Tier]:
    t1 = Tier(
        name="tier1",
        base_url=os.getenv("TIER1_BASE_URL", "http://127.0.0.1:8080/v1"),
        model=os.getenv("TIER1_MODEL", "qwen2.5-3b"),
        no_think=True,
    )
    t2 = Tier(
        name="tier2",
        base_url=os.getenv("TIER2_BASE_URL", "http://127.0.0.1:8081/v1"),
        model=os.getenv("TIER2_MODEL", "qwen3.6-35b-a3b"),
        no_think=True,
    )
    return t1, t2


# ---------- pipelines ----------


def run_mono(t1: Tier, t2: Tier, user_text: str) -> dict:
    """Один вызов T1 на полную схему."""
    answer, meta = _call(
        t1.client, t1.model, SYS_MONO, user_text,
        grammar=GBNF_FULL, no_think=t1.no_think, max_tokens=200,
    )
    parsed = _safe_json(answer) or {}
    return {
        "mode": "mono",
        "answer": answer,
        "parsed": parsed,
        "calls": [
            {"stage": "mono", "tier": t1.name, "latency_ms": meta["latency_ms"],
             "tokens_in": meta["tokens_in"], "tokens_out": meta["tokens_out"]},
        ],
        "tier_usage": {"tier1": 1, "tier2": 0},
        "total_latency_ms": meta["latency_ms"],
        "total_tokens": meta["tokens_in"] + meta["tokens_out"],
    }


def run_multi(t1: Tier, t2: Tier, user_text: str) -> dict:
    """Три этапа: normalize → 3×classify → extract+route."""
    calls: list[dict] = []
    tier_usage = {"tier1": 0, "tier2": 0}

    # --- этап 1: нормализация ---
    norm_answer, m1 = _call(
        t1.client, t1.model, SYS_NORMALIZE, user_text,
        grammar=GBNF_NORMALIZE, no_think=t1.no_think, max_tokens=300,
    )
    norm = _safe_json(norm_answer) or {}
    calls.append({"stage": "normalize", "tier": t1.name, **m1})
    tier_usage["tier1"] += 1

    # компактный блок для этапов 2-3 (если нормализация удалась)
    if norm:
        norm_block = (
            f"lang={norm.get('lang', '?')}\n"
            f"summary={norm.get('summary', '')}\n"
            f"keywords={','.join(norm.get('keywords') or [])}\n"
            f"has_error_msg={str(norm.get('has_error_msg', False)).lower()}\n\n"
            f"Исходный тикет:\n{user_text}"
        )
    else:
        norm_block = user_text  # fallback: нормализация сломалась — даём сырой тикет

    # --- этап 2: 3 enum-вызова ---
    cat_a, mc = _call(
        t1.client, t1.model, SYS_CATEGORY, norm_block,
        grammar=GBNF_ENUM_CATEGORY, no_think=t1.no_think, max_tokens=40,
    )
    sent_a, ms = _call(
        t1.client, t1.model, SYS_SENTIMENT, norm_block,
        grammar=GBNF_ENUM_SENTIMENT, no_think=t1.no_think, max_tokens=40,
    )
    sev_a, msv = _call(
        t1.client, t1.model, SYS_SEVERITY, norm_block,
        grammar=GBNF_ENUM_SEVERITY, no_think=t1.no_think, max_tokens=40,
    )
    calls.append({"stage": "category", "tier": t1.name, **mc})
    calls.append({"stage": "sentiment", "tier": t1.name, **ms})
    calls.append({"stage": "severity", "tier": t1.name, **msv})
    tier_usage["tier1"] += 3

    cat = (_safe_json(cat_a) or {}).get("category")
    sent = (_safe_json(sent_a) or {}).get("sentiment")
    sev = (_safe_json(sev_a) or {}).get("severity")

    # --- routing для этапа 3 (правила из плана + дня 8) ---
    route_to_t2 = sev == "critical" or cat == "billing"
    route_reason = (
        ("severity=critical" if sev == "critical" else None)
        or ("category=billing" if cat == "billing" else None)
        or "default_t1"
    )

    target = t2 if route_to_t2 else t1
    ca_answer, m3 = _call(
        target.client, target.model, SYS_COMPONENT_ACTION,
        norm_block + f"\n\nИзвестно: category={cat}, severity={sev}.",
        grammar=GBNF_COMPONENT_ACTION, no_think=target.no_think, max_tokens=120,
    )
    calls.append({"stage": "extract", "tier": target.name, **m3})
    tier_usage[target.name] += 1
    ca = _safe_json(ca_answer) or {}

    parsed = {
        "category": cat,
        "sentiment": sent,
        "severity": sev,
        "affected_component": ca.get("affected_component"),
        "suggested_action": ca.get("suggested_action"),
    }

    total_latency = sum(c["latency_ms"] for c in calls)
    total_tokens = sum(c["tokens_in"] + c["tokens_out"] for c in calls)

    return {
        "mode": "multi",
        "normalized": norm,
        "parsed": parsed,
        "route_to_t2": route_to_t2,
        "route_reason": route_reason,
        "calls": calls,
        "tier_usage": tier_usage,
        "total_latency_ms": round(total_latency, 1),
        "total_tokens": total_tokens,
    }


# ---------- evaluation ----------

_TOKEN_RE = re.compile(r"[a-zа-яё0-9]+", re.IGNORECASE)


def _tokens(s: str | None) -> set[str]:
    if not s:
        return set()
    return {m.group(0).lower() for m in _TOKEN_RE.finditer(s)}


def component_match(actual: str | None, expected: str) -> tuple[bool, float]:
    """Fuzzy: Jaccard по токенам ≥ 0.25 = match."""
    a, b = _tokens(actual), _tokens(expected)
    if not a or not b:
        return False, 0.0
    inter = a & b
    union = a | b
    j = len(inter) / len(union) if union else 0.0
    return j >= 0.25, round(j, 3)


def per_field_match(parsed: dict, expected: dict) -> dict:
    cat = parsed.get("category") == expected["category"]
    sent = parsed.get("sentiment") == expected["sentiment"]
    sev = parsed.get("severity") == expected["severity"]
    act = parsed.get("suggested_action") == expected["suggested_action"]
    comp_ok, comp_j = component_match(parsed.get("affected_component"), expected["affected_component"])
    return {
        "category": cat,
        "sentiment": sent,
        "severity": sev,
        "affected_component": comp_ok,
        "affected_component_jaccard": comp_j,
        "suggested_action": act,
        "all": cat and sent and sev and comp_ok and act,
    }


# ---------- runners ----------


def load_eval() -> list[dict]:
    return [json.loads(l) for l in EVAL_FILE.read_text(encoding="utf-8").splitlines() if l.strip()]


def evaluate(mode: str, items: list[dict], t1: Tier, t2: Tier, tag: str) -> list[dict]:
    runner = run_mono if mode == "mono" else run_multi
    out_path = RESULTS / f"day9-{mode}-{tag}.jsonl"
    rows: list[dict] = []
    print(f"\n[{mode}/{tag}] {t1.model} → {t2.model} | items={len(items)}\n")
    with out_path.open("w", encoding="utf-8") as f:
        for i, item in enumerate(items, 1):
            t0 = time.perf_counter()
            res = runner(t1, t2, item["user"])
            wall = (time.perf_counter() - t0) * 1000
            match = per_field_match(res["parsed"], item["expected"])
            row = {
                "id": item["id"],
                "expected": item["expected"],
                "parsed": res["parsed"],
                "match": match,
                "tier_usage": res["tier_usage"],
                "calls": res["calls"],
                "total_latency_ms": res["total_latency_ms"],
                "wall_latency_ms": round(wall, 1),
                "total_tokens": res["total_tokens"],
                "route_reason": res.get("route_reason"),
            }
            rows.append(row)
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
            f.flush()
            mark = "✓" if match["all"] else "✗"
            t2_calls = res["tier_usage"]["tier2"]
            t2_tag = f" T2={t2_calls}" if t2_calls else ""
            print(
                f"[{i:2}/{len(items)}] {mark} {item['id']:<14} "
                f"cat={int(match['category'])} sent={int(match['sentiment'])} "
                f"sev={int(match['severity'])} comp={int(match['affected_component'])}({match['affected_component_jaccard']:.2f}) "
                f"act={int(match['suggested_action'])}"
                f" | {res['total_latency_ms']:.0f}ms{t2_tag}"
            )
    return rows


def aggregate(rows: list[dict]) -> dict:
    n = len(rows)
    if not n:
        return {}
    fields = ("category", "sentiment", "severity", "affected_component", "suggested_action")
    field_acc = {f: sum(1 for r in rows if r["match"][f]) / n for f in fields}
    all_acc = sum(1 for r in rows if r["match"]["all"]) / n
    lat = [r["total_latency_ms"] for r in rows]
    wall = [r["wall_latency_ms"] for r in rows]
    tokens = sum(r["total_tokens"] for r in rows)
    t1_calls = sum(r["tier_usage"].get("tier1", 0) for r in rows)
    t2_calls = sum(r["tier_usage"].get("tier2", 0) for r in rows)
    return {
        "n": n,
        "field_acc": field_acc,
        "all_fields_acc": all_acc,
        "avg_latency_ms": round(statistics.mean(lat), 1),
        "p50_latency_ms": round(statistics.median(lat), 1),
        "max_latency_ms": round(max(lat), 1),
        "avg_wall_ms": round(statistics.mean(wall), 1),
        "total_tokens": tokens,
        "tier1_calls": t1_calls,
        "tier2_calls": t2_calls,
    }


# ---------- report ----------


def fmt_pct(x: float) -> str:
    return f"{x*100:.1f}%"


def write_report(mono: dict | None, multi: dict | None, tag: str, items_n: int):
    out = RESULTS / f"day9-{tag}-report.md"
    lines: list[str] = []
    lines.append(f"# День 9 — Multi-stage report ({tag})\n")
    lines.append(f"Eval: **{items_n}** примеров (`data/eval/day9_eval.jsonl`).\n")
    lines.append("Поля: category, sentiment, severity, affected_component (Jaccard ≥0.25), suggested_action.\n")

    headers = ["метрика", "mono", "multi"]
    rows: list[list[str]] = []
    fields = ("category", "sentiment", "severity", "affected_component", "suggested_action")

    def cell(d: dict | None, key: str, fmt=fmt_pct) -> str:
        if not d:
            return "—"
        if key.startswith("acc:"):
            f = key.split(":", 1)[1]
            return fmt(d["field_acc"][f])
        return fmt(d[key]) if isinstance(d.get(key), float) and key not in ("avg_latency_ms", "p50_latency_ms", "max_latency_ms", "avg_wall_ms") else str(d.get(key, "—"))

    rows.append(["all-fields acc", fmt_pct(mono["all_fields_acc"]) if mono else "—", fmt_pct(multi["all_fields_acc"]) if multi else "—"])
    for f in fields:
        rows.append([f"acc {f}", fmt_pct(mono["field_acc"][f]) if mono else "—", fmt_pct(multi["field_acc"][f]) if multi else "—"])
    rows.append(["avg latency (ms)", str(mono["avg_latency_ms"]) if mono else "—", str(multi["avg_latency_ms"]) if multi else "—"])
    rows.append(["p50 latency (ms)", str(mono["p50_latency_ms"]) if mono else "—", str(multi["p50_latency_ms"]) if multi else "—"])
    rows.append(["max latency (ms)", str(mono["max_latency_ms"]) if mono else "—", str(multi["max_latency_ms"]) if multi else "—"])
    rows.append(["total tokens", str(mono["total_tokens"]) if mono else "—", str(multi["total_tokens"]) if multi else "—"])
    rows.append(["tier1 calls", str(mono["tier1_calls"]) if mono else "—", str(multi["tier1_calls"]) if multi else "—"])
    rows.append(["tier2 calls", str(mono["tier2_calls"]) if mono else "—", str(multi["tier2_calls"]) if multi else "—"])

    lines.append("## Сводка\n")
    lines.append("| " + " | ".join(headers) + " |")
    lines.append("|" + "|".join(["---"] * len(headers)) + "|")
    for r in rows:
        lines.append("| " + " | ".join(r) + " |")
    lines.append("")

    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"\nReport: {out}")


# ---------- main ----------


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["mono", "multi", "both"], default="both")
    ap.add_argument("--tag", default="default")
    args = ap.parse_args()

    items = load_eval()
    t1, t2 = build_tiers()

    print(f"Eval items: {len(items)} | T1={t1.model} T2={t2.model}")

    mono_agg = multi_agg = None

    if args.mode in ("mono", "both"):
        rows_mono = evaluate("mono", items, t1, t2, args.tag)
        mono_agg = aggregate(rows_mono)
        print(f"\n[mono] all-fields acc={fmt_pct(mono_agg['all_fields_acc'])} avg_lat={mono_agg['avg_latency_ms']}ms tokens={mono_agg['total_tokens']}")

    if args.mode in ("multi", "both"):
        rows_multi = evaluate("multi", items, t1, t2, args.tag)
        multi_agg = aggregate(rows_multi)
        print(f"\n[multi] all-fields acc={fmt_pct(multi_agg['all_fields_acc'])} avg_lat={multi_agg['avg_latency_ms']}ms tokens={multi_agg['total_tokens']} T2_calls={multi_agg['tier2_calls']}")

    write_report(mono_agg, multi_agg, args.tag, len(items))


if __name__ == "__main__":
    main()
