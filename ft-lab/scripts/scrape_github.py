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
    ("JetBrains", "kotlin"),
]

PER_REPO = 8
OUT = Path(__file__).resolve().parent.parent / "data" / "raw" / "github_issues.jsonl"

MIN_LEN = 60
MAX_LEN = 1500


def fetch(repo_owner: str, repo_name: str, token: str | None) -> list[dict]:
    url = f"https://api.github.com/repos/{repo_owner}/{repo_name}/issues"
    params = {"state": "closed", "per_page": 30, "sort": "comments", "direction": "desc"}
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    r = httpx.get(url, params=params, headers=headers, timeout=30)
    r.raise_for_status()
    out = []
    for issue in r.json():
        if "pull_request" in issue:
            continue
        title = (issue.get("title") or "").strip()
        body = (issue.get("body") or "").strip()
        text = f"{title}\n\n{body}".strip()
        if not (MIN_LEN <= len(text) <= MAX_LEN):
            continue
        out.append({
            "id": f"{repo_owner}/{repo_name}#{issue['number']}",
            "title": title,
            "body": body,
            "url": issue["html_url"],
        })
        if len(out) >= PER_REPO:
            break
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
