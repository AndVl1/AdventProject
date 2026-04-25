"""Общая схема для всех дней. Источник правды — здесь."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

CATEGORIES = ("bug", "feature_request", "billing", "how_to", "other")
SENTIMENTS = ("neg", "neu", "pos")

Category = Literal["bug", "feature_request", "billing", "how_to", "other"]
Sentiment = Literal["neg", "neu", "pos"]


class Ticket(BaseModel):
    category: Category
    sentiment: Sentiment


SYSTEM_PROMPT = (
    "Ты классифицируешь тикеты поддержки. "
    "Верни строго JSON: {\"category\": one of [bug, feature_request, billing, how_to, other], "
    "\"sentiment\": one of [neg, neu, pos]}. Без обрамляющего текста."
)


def build_messages(user_text: str, assistant_json: str | None = None) -> list[dict]:
    msgs: list[dict] = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_text},
    ]
    if assistant_json is not None:
        msgs.append({"role": "assistant", "content": assistant_json})
    return msgs


# GBNF grammar для llama-server: жёсткий enum
GBNF_TICKET = r"""
root   ::= "{" ws "\"category\"" ws ":" ws cat ws "," ws "\"sentiment\"" ws ":" ws sent ws "}"
cat    ::= "\"bug\"" | "\"feature_request\"" | "\"billing\"" | "\"how_to\"" | "\"other\""
sent   ::= "\"neg\"" | "\"neu\"" | "\"pos\""
ws     ::= [ \t\n]*
""".strip()
