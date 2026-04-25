"""Тянет issues из публичных репо. Сохраняет в data/raw/github_issues.jsonl без меток.

Размечать вручную — открыть файл, добавить поле "label" к каждой строке:
{"id": ..., "title": ..., "body": ..., "label": {"category": "...", "sentiment": "..."}}
Затем сохранить как data/raw/labeled.jsonl.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import httpx
from dotenv import load_dotenv

load_dotenv()

REPOS = [
    ("ktorio", "ktor"),
    ("Kotlin", "kotlinx.serialization"),
    ("Kotlin", "kotlinx.coroutines"),
    ("square", "okhttp"),
    ("square", "retrofit"),
    ("InsertKoinIO", "koin"),
]

PER_REPO = 6
OUT = Path(__file__).resolve().parent.parent / "data" / "raw" / "github_issues.jsonl"

MIN_LEN = 40
MAX_LEN = 4000  # обрезаем длинные, не отбрасываем


def normalize(text: str) -> str:
    """Чистим markdown-шум, обрезаем длинные тела."""
    if not text:
        return ""
    text = text.replace("\r\n", "\n").strip()
    if len(text) > MAX_LEN:
        text = text[:MAX_LEN].rsplit("\n", 1)[0] + "\n[...truncated]"
    return text


def is_spam_or_html(text: str) -> bool:
    """Отсекаем HTML-портянки, спам-pitch-deck'и, чистый код без вопроса."""
    low = text.lower()
    if "<!doctype" in low or "<html" in low:
        return True
    # слишком много html-тегов
    tag_count = low.count("<") + low.count("</")
    if tag_count > 30 and len(text) > 500:
        return True
    # чисто CSS/JS блок без описания
    if low.count("{") > 15 and low.count("}") > 15 and "function" not in low and "issue" not in low:
        return True
    return False


def fetch(repo_owner: str, repo_name: str, token: str | None) -> list[dict]:
    url = f"https://api.github.com/repos/{repo_owner}/{repo_name}/issues"
    params = {
        "state": "all",
        "per_page": 50,
        "sort": "created",
        "direction": "desc",
    }
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    r = httpx.get(url, params=params, headers=headers, timeout=30)
    r.raise_for_status()
    raw = r.json()
    if not isinstance(raw, list):
        print(f"[WARN] {repo_owner}/{repo_name}: ответ не список: {raw}", file=sys.stderr)
        return []

    seen_pr = 0
    seen_short = 0
    seen_spam = 0
    out: list[dict] = []
    for issue in raw:
        if "pull_request" in issue:
            seen_pr += 1
            continue
        title = (issue.get("title") or "").strip()
        body = normalize(issue.get("body") or "")
        text = f"{title}\n\n{body}".strip() if body else title
        if len(text) < MIN_LEN:
            seen_short += 1
            continue
        if is_spam_or_html(text):
            seen_spam += 1
            continue
        out.append({
            "id": f"{repo_owner}/{repo_name}#{issue['number']}",
            "title": title,
            "body": body,
            "text": text,
            "url": issue["html_url"],
        })
        if len(out) >= PER_REPO:
            break

    print(
        f"  raw={len(raw)} pr_skip={seen_pr} short_skip={seen_short} "
        f"spam_skip={seen_spam} kept={len(out)}",
        file=sys.stderr,
    )
    return out


def main() -> int:
    token = os.getenv("GITHUB_TOKEN") or None
    OUT.parent.mkdir(parents=True, exist_ok=True)
    total = 0
    with OUT.open("w", encoding="utf-8") as f:
        for owner, name in REPOS:
            try:
                items = fetch(owner, name, token)
            except Exception as e:
                print(f"[WARN] {owner}/{name}: {e}", file=sys.stderr)
                continue
            for it in items:
                f.write(json.dumps(it, ensure_ascii=False) + "\n")
            total += len(items)
            print(f"{owner}/{name}: {len(items)}")
    print(f"\nИтого: {total} issues -> {OUT}")
    print("Следующий шаг: разметить вручную и сохранить в data/raw/labeled.jsonl")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
