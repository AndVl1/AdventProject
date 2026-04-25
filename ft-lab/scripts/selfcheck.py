"""День 7 — Self-check (chain-of-verification lite).

Этап А: модель выдаёт {category, sentiment, reason} — JSON со свободным reason.
Этап Б: новый запрос с тем же тикетом и предложенной классификацией —
       «согласен ли ты с этой классификацией? верни {agree: bool, fix?: {...}}».

Расхождение между А и Б → status UNSURE с финальной меткой из Б (fix).
Согласие → OK.

Для устойчивости формата используем GBNF на обоих этапах.
"""
from __future__ import annotations

import json
import time

from openai import OpenAI

from schema import build_messages

# GBNF этапа А: {category, sentiment, reason} — reason произвольная строка
GBNF_REASONED = r"""
root    ::= "{" ws "\"category\"" ws ":" ws cat ws "," ws "\"sentiment\"" ws ":" ws sent ws "," ws "\"reason\"" ws ":" ws str ws "}"
cat     ::= "\"bug\"" | "\"feature_request\"" | "\"billing\"" | "\"how_to\"" | "\"other\""
sent    ::= "\"neg\"" | "\"neu\"" | "\"pos\""
str     ::= "\"" char* "\""
char    ::= [^"\\] | "\\" ["\\/bfnrt]
ws      ::= [ \t\n]*
""".strip()

# GBNF этапа Б: {agree: bool, fix?: {category, sentiment}}
GBNF_VERIFY = r"""
root    ::= "{" ws "\"agree\"" ws ":" ws bool ws ("," ws "\"fix\"" ws ":" ws fix ws)? "}"
bool    ::= "true" | "false"
fix     ::= "{" ws "\"category\"" ws ":" ws cat ws "," ws "\"sentiment\"" ws ":" ws sent ws "}"
cat     ::= "\"bug\"" | "\"feature_request\"" | "\"billing\"" | "\"how_to\"" | "\"other\""
sent    ::= "\"neg\"" | "\"neu\"" | "\"pos\""
ws      ::= [ \t\n]*
""".strip()


VERIFY_SYSTEM = (
    "Ты валидируешь классификацию тикета. Тебе дан текст тикета и предложенная "
    "классификация. Верни JSON: {\"agree\": bool}. Если не согласен — "
    "{\"agree\": false, \"fix\": {\"category\": ..., \"sentiment\": ...}}. "
    "Без лишнего текста."
)


def _strip_think(content: str) -> str:
    if "<think>" in content and "</think>" in content:
        return content[content.rfind("</think>") + len("</think>"):].strip()
    return content


def _call(
    client: OpenAI,
    model: str,
    messages: list[dict],
    *,
    grammar: str,
    no_think: bool,
) -> tuple[str, dict]:
    extra: dict = {"grammar": grammar}
    if no_think:
        extra["chat_template_kwargs"] = {"enable_thinking": False}
    t0 = time.perf_counter()
    resp = client.chat.completions.create(
        model=model,
        messages=messages,
        temperature=0,
        top_p=1,
        max_tokens=400,
        extra_body=extra,
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


def selfcheck(
    client: OpenAI,
    model: str,
    user_text: str,
    *,
    no_think: bool = False,
) -> dict:
    """Двухэтапный self-check.

    Returns: {
      status: OK | UNSURE | FAIL,
      stage_a: {category, sentiment, reason},
      stage_b: {agree, fix?},
      final: {category, sentiment} — после fix если был,
      latency_ms, tokens_in, tokens_out, attempts
    }
    """
    msgs_a = build_messages(user_text)
    # система этапа А — расширяем чтобы reason был
    msgs_a[0] = {
        "role": "system",
        "content": (
            "Ты классифицируешь тикеты поддержки. Верни строго JSON: "
            "{\"category\": one of [bug, feature_request, billing, how_to, other], "
            "\"sentiment\": one of [neg, neu, pos], "
            "\"reason\": краткое обоснование одним предложением}. "
            "Без обрамляющего текста."
        ),
    }

    total_lat = 0.0
    total_in = 0
    total_out = 0

    answer_a, meta_a = _call(client, model, msgs_a, grammar=GBNF_REASONED, no_think=no_think)
    total_lat += meta_a["latency_ms"]
    total_in += meta_a["tokens_in"]
    total_out += meta_a["tokens_out"]

    try:
        stage_a = json.loads(answer_a)
    except Exception as e:
        return {
            "status": "FAIL",
            "stage_a": {"raw": answer_a, "error": str(e)},
            "stage_b": None,
            "final": None,
            "latency_ms": round(total_lat, 1),
            "tokens_in": total_in,
            "tokens_out": total_out,
            "attempts": 1,
        }

    proposal = {
        "category": stage_a.get("category"),
        "sentiment": stage_a.get("sentiment"),
    }
    msgs_b = [
        {"role": "system", "content": VERIFY_SYSTEM},
        {
            "role": "user",
            "content": (
                f"Тикет:\n{user_text}\n\n"
                f"Предложенная классификация: {json.dumps(proposal, ensure_ascii=False)}\n\n"
                f"Аргументация: {stage_a.get('reason', '')}\n\n"
                "Согласен?"
            ),
        },
    ]

    answer_b, meta_b = _call(client, model, msgs_b, grammar=GBNF_VERIFY, no_think=no_think)
    total_lat += meta_b["latency_ms"]
    total_in += meta_b["tokens_in"]
    total_out += meta_b["tokens_out"]

    try:
        stage_b = json.loads(answer_b)
    except Exception as e:
        return {
            "status": "FAIL",
            "stage_a": stage_a,
            "stage_b": {"raw": answer_b, "error": str(e)},
            "final": proposal,
            "latency_ms": round(total_lat, 1),
            "tokens_in": total_in,
            "tokens_out": total_out,
            "attempts": 2,
        }

    agree = bool(stage_b.get("agree"))
    fix = stage_b.get("fix")
    if agree:
        status = "OK"
        final = proposal
    else:
        status = "UNSURE"
        final = fix if fix else proposal

    return {
        "status": status,
        "stage_a": stage_a,
        "stage_b": stage_b,
        "final": final,
        "latency_ms": round(total_lat, 1),
        "tokens_in": total_in,
        "tokens_out": total_out,
        "attempts": 2,
    }
