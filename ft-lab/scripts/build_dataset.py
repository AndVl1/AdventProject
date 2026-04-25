"""Сборка финального датасета.

Читает реальные (labeled.jsonl) + синтетические (synthetic.jsonl), конвертирует
в OpenAI-JSONL формат (messages: system + user + assistant), дедуплицирует по
нормализованному тексту, фильтрует по длине, делает stratified 80/20 split
по category.

Выход: data/train.jsonl, data/valid.jsonl
"""
from __future__ import annotations

import hashlib
import json
import random
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from schema import CATEGORIES, SENTIMENTS, build_messages

ROOT = Path(__file__).resolve().parent.parent
LABELED = ROOT / "data" / "raw" / "labeled.jsonl"
SYNTHETIC = ROOT / "data" / "synthetic" / "synthetic.jsonl"
TRAIN = ROOT / "data" / "train.jsonl"
VALID = ROOT / "data" / "valid.jsonl"

MIN_LEN = 40
MAX_LEN = 1500
VALID_RATIO = 0.20
SEED = 42


def load_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def normalize(text: str) -> str:
    """Для дедупа: lowercase, схлопнуть пробелы, убрать пунктуацию по краям."""
    t = text.lower()
    t = re.sub(r"\s+", " ", t).strip()
    return t


def text_hash(text: str) -> str:
    return hashlib.sha1(normalize(text).encode("utf-8")).hexdigest()


def to_chat(item: dict) -> dict:
    """item: {id, text, label: {category, sentiment}} -> {messages: [...], _meta: ...}"""
    text = item["text"]
    label = item["label"]
    assistant = json.dumps(
        {"category": label["category"], "sentiment": label["sentiment"]},
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return {
        "messages": build_messages(text, assistant),
        "_meta": {"id": item["id"], "category": label["category"], "sentiment": label["sentiment"]},
    }


def stratified_split(
    items: list[dict], ratio: float, seed: int
) -> tuple[list[dict], list[dict]]:
    """Стратификация по category. Внутри группы случайный shuffle."""
    rng = random.Random(seed)
    by_cat: dict[str, list[dict]] = defaultdict(list)
    for it in items:
        by_cat[it["_meta"]["category"]].append(it)

    train: list[dict] = []
    valid: list[dict] = []
    for cat, group in by_cat.items():
        rng.shuffle(group)
        n_valid = max(1, round(len(group) * ratio)) if len(group) > 1 else 0
        valid.extend(group[:n_valid])
        train.extend(group[n_valid:])

    rng.shuffle(train)
    rng.shuffle(valid)
    return train, valid


def main() -> int:
    real = load_jsonl(LABELED)
    synth = load_jsonl(SYNTHETIC)
    print(f"Реальные:    {len(real)}  ({LABELED.name})")
    print(f"Синтетика:   {len(synth)}  ({SYNTHETIC.name})")

    if not real and not synth:
        print("Нет данных. Запусти label_cli.py и/или gen_synthetic.py", file=sys.stderr)
        return 1

    # 1. Объединить + валидировать поля + дедуп
    merged: list[dict] = []
    seen_hashes: set[str] = set()
    drops = Counter()

    for src_name, src in [("real", real), ("synth", synth)]:
        for item in src:
            if "text" not in item or "label" not in item:
                drops["bad_format"] += 1
                continue
            text = (item["text"] or "").strip()
            cat = item["label"].get("category")
            sent = item["label"].get("sentiment")
            if cat not in CATEGORIES or sent not in SENTIMENTS:
                drops["bad_label"] += 1
                continue
            if not (MIN_LEN <= len(text) <= MAX_LEN):
                drops["bad_length"] += 1
                continue
            h = text_hash(text)
            if h in seen_hashes:
                drops["dup"] += 1
                continue
            seen_hashes.add(h)
            merged.append({
                "id": item.get("id", f"{src_name}/{len(merged)}"),
                "text": text,
                "label": {"category": cat, "sentiment": sent},
            })

    print(f"\nПосле фильтра + дедупа: {len(merged)}")
    if drops:
        print(f"Отброшено: {dict(drops)}")

    # 2. Конверт в chat-формат
    chat = [to_chat(m) for m in merged]

    # 3. Стратифицированный split
    train, valid = stratified_split(chat, VALID_RATIO, SEED)

    # 4. Запись
    TRAIN.parent.mkdir(parents=True, exist_ok=True)
    with TRAIN.open("w", encoding="utf-8") as f:
        for item in train:
            f.write(json.dumps({"messages": item["messages"]}, ensure_ascii=False) + "\n")
    with VALID.open("w", encoding="utf-8") as f:
        for item in valid:
            f.write(json.dumps({"messages": item["messages"]}, ensure_ascii=False) + "\n")

    # 5. Отчёт
    def stats(items: list[dict]) -> dict:
        cats = Counter(it["_meta"]["category"] for it in items)
        sents = Counter(it["_meta"]["sentiment"] for it in items)
        return {"total": len(items), "category": dict(cats), "sentiment": dict(sents)}

    print(f"\nTrain ({TRAIN}):")
    print(f"  {stats(train)}")
    print(f"Valid ({VALID}):")
    print(f"  {stats(valid)}")

    # Запоминаем _meta отдельно для отладки
    meta_path = ROOT / "data" / "split_meta.json"
    meta_path.write_text(json.dumps({
        "train": [it["_meta"] for it in train],
        "valid": [it["_meta"] for it in valid],
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nMeta: {meta_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
