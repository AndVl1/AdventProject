"""Интерактивная разметка тикетов.

Читает data/raw/github_issues.jsonl, для каждой записи показывает текст
и просит выбрать category + sentiment одной клавишей. Сохраняет в
data/raw/labeled.jsonl с поддержкой резюма (повторный запуск продолжает
с первого неразмеченного id).

Использование:
    python scripts/label_cli.py
    python scripts/label_cli.py --target 15   # цель по количеству
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "data" / "raw" / "github_issues.jsonl"
DST = ROOT / "data" / "raw" / "labeled.jsonl"

CATEGORY_KEYS = {
    "1": "bug",
    "2": "feature_request",
    "3": "billing",
    "4": "how_to",
    "5": "other",
}
SENTIMENT_KEYS = {"1": "neg", "2": "neu", "3": "pos"}

BODY_LIMIT = 1200


def load_done() -> set[str]:
    if not DST.exists():
        return set()
    done = set()
    for line in DST.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            done.add(json.loads(line)["id"])
        except Exception:
            pass
    return done


def prompt(label: str, mapping: dict[str, str]) -> str | None:
    options = " ".join(f"{k}={v}" for k, v in mapping.items())
    while True:
        ans = input(f"  {label} [{options}] (s=skip, q=quit): ").strip().lower()
        if ans == "q":
            return "QUIT"
        if ans == "s":
            return None
        if ans in mapping:
            return mapping[ans]
        print(f"    неверный ввод: {ans!r}")


def show(item: dict, idx: int, total: int, kept: int, target: int) -> None:
    print("\n" + "=" * 72)
    print(f"[{idx}/{total}] размечено: {kept}/{target}   id: {item['id']}")
    print(f"url: {item['url']}")
    print("-" * 72)
    body = item.get("text") or item.get("body") or ""
    if len(body) > BODY_LIMIT:
        body = body[:BODY_LIMIT] + "\n[...обрезано]"
    print(body)
    print("-" * 72)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", type=int, default=15, help="целевое кол-во размеченных")
    args = parser.parse_args()

    if not SRC.exists():
        print(f"Нет файла {SRC}. Сначала запусти scrape_github.py", file=sys.stderr)
        return 1

    items = [json.loads(l) for l in SRC.read_text(encoding="utf-8").splitlines() if l.strip()]
    done = load_done()
    print(f"Источник: {len(items)} тикетов. Уже размечено: {len(done)}. Цель: {args.target}.")

    DST.parent.mkdir(parents=True, exist_ok=True)
    out_f = DST.open("a", encoding="utf-8")

    kept = len(done)
    try:
        for idx, item in enumerate(items, 1):
            if item["id"] in done:
                continue
            if kept >= args.target:
                print(f"\nДостигнута цель {args.target}. Стоп.")
                break

            show(item, idx, len(items), kept, args.target)

            cat = prompt("category", CATEGORY_KEYS)
            if cat == "QUIT":
                break
            if cat is None:
                continue
            sent = prompt("sentiment", SENTIMENT_KEYS)
            if sent == "QUIT":
                break
            if sent is None:
                continue

            row = {
                "id": item["id"],
                "text": item.get("text") or item.get("body", ""),
                "label": {"category": cat, "sentiment": sent},
            }
            out_f.write(json.dumps(row, ensure_ascii=False) + "\n")
            out_f.flush()
            kept += 1
            print(f"  ✓ сохранено: {cat}/{sent}")
    finally:
        out_f.close()

    print(f"\nИтого размечено: {kept} -> {DST}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
