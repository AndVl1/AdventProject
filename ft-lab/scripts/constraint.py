"""День 7 — Constraint-based check.

Логика:
1. Первый проход БЕЗ grammar — ловим ошибки формата как они есть.
2. Парсим, валидируем по pydantic Ticket (enum-проверка).
3. Если fail — retry с remediation prompt: "твой предыдущий ответ X невалиден,
   верни строго JSON по схеме".
4. Если retry тоже fail — fallback на GBNF (жёсткая маска), это финальная страховка.

Метрики per-row:
- pass_first: парсинг прошёл с первого вызова без grammar
- retried: понадобился ремедиэйшен
- fallback_grammar: пришлось включать GBNF
- final_status: OK / FAIL
- attempts: 1..3
"""
from __future__ import annotations

import json
import time
from typing import Any

from openai import OpenAI
from pydantic import ValidationError

from schema import GBNF_TICKET, Ticket, build_messages

REMEDIATION = (
    "Твой предыдущий ответ невалиден. "
    "Верни СТРОГО один JSON-объект без обрамляющего текста, "
    "по схеме {\"category\": one of [bug, feature_request, billing, how_to, other], "
    "\"sentiment\": one of [neg, neu, pos]}."
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
    grammar: str | None,
    no_think: bool,
    max_tokens: int = 200,
) -> tuple[str, dict]:
    extra: dict = {}
    if grammar:
        extra["grammar"] = grammar
    if no_think:
        extra["chat_template_kwargs"] = {"enable_thinking": False}
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


def _try_parse(text: str) -> tuple[bool, dict | None, str]:
    """Returns (ok, parsed_dict, error_msg)."""
    try:
        obj = json.loads(text)
    except Exception as e:
        return False, None, f"json: {e}"
    try:
        Ticket(**obj)
    except ValidationError as e:
        return False, obj, f"schema: {e.errors()[0]['msg']}"
    except TypeError as e:
        return False, obj if isinstance(obj, dict) else None, f"type: {e}"
    return True, obj, ""


def constraint_check(
    client: OpenAI,
    model: str,
    user_text: str,
    *,
    no_think: bool = False,
) -> dict:
    """Прогон с трёхступенчатой проверкой формата.

    Возвращает: {
      status: OK|FAIL,
      attempts: int,
      pass_first: bool,
      retried: bool,
      fallback_grammar: bool,
      answer: str | None,
      parsed: dict | None,
      error: str | None,
      latency_ms: float,
      tokens_in: int,
      tokens_out: int,
    }
    """
    msgs = build_messages(user_text)
    total_lat = 0.0
    total_in = 0
    total_out = 0
    attempts = 0

    # 1) free-form, без grammar
    answer, meta = _call(client, model, msgs, grammar=None, no_think=no_think)
    attempts += 1
    total_lat += meta["latency_ms"]
    total_in += meta["tokens_in"]
    total_out += meta["tokens_out"]
    ok, parsed, err = _try_parse(answer)
    if ok:
        return {
            "status": "OK",
            "attempts": attempts,
            "pass_first": True,
            "retried": False,
            "fallback_grammar": False,
            "answer": answer,
            "parsed": parsed,
            "error": None,
            "latency_ms": round(total_lat, 1),
            "tokens_in": total_in,
            "tokens_out": total_out,
        }

    # 2) remediation retry — добавляем в историю предыдущий ответ + замечание
    msgs_retry = msgs + [
        {"role": "assistant", "content": answer},
        {"role": "user", "content": REMEDIATION},
    ]
    answer2, meta2 = _call(client, model, msgs_retry, grammar=None, no_think=no_think)
    attempts += 1
    total_lat += meta2["latency_ms"]
    total_in += meta2["tokens_in"]
    total_out += meta2["tokens_out"]
    ok2, parsed2, err2 = _try_parse(answer2)
    if ok2:
        return {
            "status": "OK",
            "attempts": attempts,
            "pass_first": False,
            "retried": True,
            "fallback_grammar": False,
            "answer": answer2,
            "parsed": parsed2,
            "error": None,
            "latency_ms": round(total_lat, 1),
            "tokens_in": total_in,
            "tokens_out": total_out,
        }

    # 3) fallback на жёсткий GBNF
    answer3, meta3 = _call(client, model, msgs, grammar=GBNF_TICKET, no_think=no_think)
    attempts += 1
    total_lat += meta3["latency_ms"]
    total_in += meta3["tokens_in"]
    total_out += meta3["tokens_out"]
    ok3, parsed3, err3 = _try_parse(answer3)
    return {
        "status": "OK" if ok3 else "FAIL",
        "attempts": attempts,
        "pass_first": False,
        "retried": True,
        "fallback_grammar": True,
        "answer": answer3 if ok3 else answer2,
        "parsed": parsed3 if ok3 else parsed2,
        "error": None if ok3 else (err3 or err2 or err),
        "latency_ms": round(total_lat, 1),
        "tokens_in": total_in,
        "tokens_out": total_out,
    }
