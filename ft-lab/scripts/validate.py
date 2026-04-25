"""Построчная валидация JSONL-датасета.

Проверяет:
1. Каждая строка — валидный JSON.
2. Ключ `messages` — массив длиной 3.
3. Роли по порядку: system, user, assistant.
4. Все content — непустые строки.
5. assistant.content парсится как JSON и матчит схему Ticket
   (pydantic Literal на category и sentiment).

Exit code: 0 если всё ок, 1 при первой ошибке (с указанием файла и строки).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pydantic import ValidationError
from schema import Ticket

ROOT = Path(__file__).resolve().parent.parent
FILES = [ROOT / "data" / "train.jsonl", ROOT / "data" / "valid.jsonl"]

REQUIRED_ROLES = ("system", "user", "assistant")


class ValidateError(Exception):
    pass


def check_line(obj: dict) -> None:
    if "messages" not in obj:
        raise ValidateError("отсутствует ключ 'messages'")
    msgs = obj["messages"]
    if not isinstance(msgs, list):
        raise ValidateError("'messages' не массив")
    if len(msgs) != 3:
        raise ValidateError(f"длина messages={len(msgs)}, ожидалось 3")

    for i, (msg, expected_role) in enumerate(zip(msgs, REQUIRED_ROLES)):
        if not isinstance(msg, dict):
            raise ValidateError(f"messages[{i}] не объект")
        if msg.get("role") != expected_role:
            raise ValidateError(f"messages[{i}].role={msg.get('role')!r}, ожидалось {expected_role!r}")
        content = msg.get("content")
        if not isinstance(content, str) or not content.strip():
            raise ValidateError(f"messages[{i}].content пустой или не строка")

    assistant_content = msgs[2]["content"]
    try:
        parsed = json.loads(assistant_content)
    except json.JSONDecodeError as e:
        raise ValidateError(f"assistant.content не JSON: {e}")

    try:
        Ticket(**parsed)
    except ValidationError as e:
        raise ValidateError(f"assistant.content не матчит схему Ticket: {e.errors()}")


def validate_file(path: Path) -> tuple[int, list[str]]:
    if not path.exists():
        return 0, [f"файл не найден: {path}"]
    errors: list[str] = []
    n_ok = 0
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError as e:
            errors.append(f"{path.name}:{lineno} — невалидный JSON: {e}")
            continue
        try:
            check_line(obj)
            n_ok += 1
        except ValidateError as e:
            errors.append(f"{path.name}:{lineno} — {e}")
    return n_ok, errors


def main() -> int:
    total_ok = 0
    total_err = 0
    for path in FILES:
        n_ok, errors = validate_file(path)
        if errors:
            print(f"[FAIL] {path.name}: {n_ok} ok, {len(errors)} ошибок")
            for err in errors[:10]:
                print(f"  {err}")
            if len(errors) > 10:
                print(f"  ... и ещё {len(errors) - 10}")
            total_err += len(errors)
        else:
            print(f"[OK]   {path.name}: {n_ok} строк")
        total_ok += n_ok

    print(f"\nИтого: {total_ok} валидных, {total_err} ошибок")
    return 1 if total_err else 0


if __name__ == "__main__":
    raise SystemExit(main())
