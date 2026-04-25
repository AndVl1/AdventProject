"""Генерация синтетических тикетов через Qwen3-30B-A3B (Tier 2 teacher).

Требует запущенного llama-server:
    llama-server -m /path/to/Qwen3-30B-A3B-UD-Q3_K_S.gguf \
        --host 127.0.0.1 --port 8081 --n-gpu-layers 999 --ctx-size 8192

Стратегия — балансировка датасета. Читает data/raw/labeled.jsonl,
считает распределение по парам (category, sentiment), для каждой пары
дополняет синтетикой до --target-per-combo. Итог пишет в
data/synthetic/synthetic.jsonl.

Использование:
    python scripts/gen_synthetic.py                          # default target=5
    python scripts/gen_synthetic.py --target-per-combo 4 --resume
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
import traceback
from collections import Counter
from pathlib import Path

from dotenv import load_dotenv
from openai import OpenAI
from tqdm import tqdm

load_dotenv()

ROOT = Path(__file__).resolve().parent.parent
LABELED = ROOT / "data" / "raw" / "labeled.jsonl"
OUT = ROOT / "data" / "synthetic" / "synthetic.jsonl"

CATEGORIES = ["bug", "feature_request", "billing", "how_to", "other"]
SENTIMENTS = ["neg", "neu", "pos"]

TOPICS = [
    "OAuth2 авторизация и refresh token",
    "websocket внезапно дисконнектится",
    "приложение крашится на старте после обновления",
    "тёмная тема, цвета не применяются",
    "подписка premium не активируется после оплаты",
    "double-charge на месячном тарифе",
    "Ktor client retry-policy для нестабильного API",
    "кастомный сериализатор для polymorphic JSON",
    "kotlinx.coroutines flow + lifecycle scope",
    "deeplink не открывает нужный экран",
    "memory leak при повороте экрана",
    "медленный старт холодного запуска iOS",
    "неверный invoice в личном кабинете",
    "скачивание большого файла, прогресс-бар",
    "OkHttp interceptor и токен в header",
    "Retrofit Call.cancel() висит",
    "Koin module не инициализируется в тесте",
    "ProGuard правила для библиотеки",
    "TLS pinning, certificate change",
    "локализация на русский, fallback en",
    "background sync на Android 14",
    "iOS push-уведомления не приходят",
    "ANR при работе с базой данных",
    "Compose recomposition бесконечный цикл",
    "BillingClient connection state",
    "промокод не применяется на чекауте",
    "виджет на главном экране пропадает",
    "fingerprint авторизация на новых устройствах",
    "deep linking после убийства приложения",
    "geolocation permission на iOS 17",
]

CATEGORY_HINT = {
    "bug": "пользователь столкнулся с поломкой / некорректным поведением",
    "feature_request": "пользователь просит добавить функциональность",
    "billing": "вопрос про оплату / подписку / возврат / счёт",
    "how_to": "пользователь спрашивает как что-то сделать",
    "other": "общий вопрос / отзыв / не подпадает под другие категории",
}

SENTIMENT_HINT = {
    "neg": "раздражённый, фрустрированный, жалуется",
    "neu": "нейтральный, технический отчёт без эмоций",
    "pos": "благодарный, дружелюбный, хвалит продукт",
}

PROMPT_TMPL = """Сгенерируй текст тикета в саппорт от лица реального пользователя.

Категория: {category} ({cat_hint})
Тональность: {sentiment} ({sent_hint})
Тема: {topic}

Требования:
- 2–5 предложений
- естественный язык (русский ИЛИ английский — выбирай рандомно)
- никаких маркдаун-заголовков, списков, префиксов "Title:", "Body:"
- только текст тикета, ничего больше
"""


def load_labeled_distribution() -> Counter:
    if not LABELED.exists():
        return Counter()
    items = []
    for line in LABELED.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            items.append(json.loads(line))
    return Counter((it["label"]["category"], it["label"]["sentiment"]) for it in items)


def load_existing_synth() -> Counter:
    if not OUT.exists():
        return Counter()
    counter: Counter = Counter()
    for line in OUT.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            obj = json.loads(line)
            counter[(obj["label"]["category"], obj["label"]["sentiment"])] += 1
    return counter


def call_teacher(client: OpenAI, model: str, prompt: str) -> str:
    resp = client.chat.completions.create(
        model=model,
        messages=[
            {"role": "system", "content": "Ты помощник который генерирует реалистичные тикеты для датасета."},
            {"role": "user", "content": prompt},
        ],
        temperature=0.85,
        top_p=0.95,
        max_tokens=300,
    )
    return resp.choices[0].message.content.strip()


def clean(text: str) -> str:
    """Убираем кавычки-обрамление, префиксы типа 'Тикет:'."""
    text = text.strip().strip('"').strip("'").strip()
    for prefix in ("Тикет:", "Текст:", "Ticket:", "Body:", "Title:"):
        if text.lower().startswith(prefix.lower()):
            text = text[len(prefix):].strip()
    return text


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target-per-combo", type=int, default=5,
                        help="целевое суммарное (labeled+synth) кол-во на пару (cat,sent)")
    parser.add_argument("--resume", action="store_true",
                        help="не перегенерировать уже существующее в synthetic.jsonl")
    parser.add_argument("--strict", action="store_true",
                        help="стоп на первой ошибке с полным traceback")
    parser.add_argument("--limit", type=int, default=None,
                        help="максимум запросов суммарно (для отладки)")
    args = parser.parse_args()

    base_url = os.getenv("TIER2_BASE_URL", "http://127.0.0.1:8081/v1")
    api_key = os.getenv("TIER2_API_KEY", "local")
    model = os.getenv("TIER2_MODEL", "qwen3-30b-a3b")

    client = OpenAI(base_url=base_url, api_key=api_key, timeout=120.0)

    labeled = load_labeled_distribution()
    existing = load_existing_synth() if args.resume else Counter()

    OUT.parent.mkdir(parents=True, exist_ok=True)
    mode = "a" if args.resume else "w"

    print(f"Teacher: {model} @ {base_url}")
    print(f"Labeled distribution: {dict(labeled)}")
    print(f"Existing synthetic: {dict(existing)}")
    print(f"Target per combo: {args.target_per_combo}")
    print()

    plan: dict[tuple[str, str], int] = {}
    for cat in CATEGORIES:
        for sent in SENTIMENTS:
            have = labeled[(cat, sent)] + existing[(cat, sent)]
            need = max(0, args.target_per_combo - have)
            if need:
                plan[(cat, sent)] = need

    total_to_gen = sum(plan.values())
    print(f"К генерации: {total_to_gen} примеров по {len(plan)} комбинациям\n")

    if total_to_gen == 0:
        print("Всё уже сбалансировано. Выход.")
        return 0

    rng = random.Random(42)
    out_f = OUT.open(mode, encoding="utf-8")
    pbar = tqdm(total=total_to_gen, desc="generate", file=sys.stderr)
    written = 0
    failed = 0

    done_count = 0
    try:
        for (cat, sent), need in plan.items():
            for i in range(need):
                if args.limit is not None and done_count >= args.limit:
                    break
                done_count += 1
                topic = rng.choice(TOPICS)
                prompt = PROMPT_TMPL.format(
                    category=cat,
                    cat_hint=CATEGORY_HINT[cat],
                    sentiment=sent,
                    sent_hint=SENTIMENT_HINT[sent],
                    topic=topic,
                )
                try:
                    raw = call_teacher(client, model, prompt)
                    text = clean(raw)
                    if len(text) < 30:
                        failed += 1
                        tqdm.write(f"[SHORT] {cat}/{sent} #{i}: len={len(text)} raw={raw!r}")
                        pbar.update(1)
                        continue
                    idx = existing[(cat, sent)] + i
                    row = {
                        "id": f"synth/{cat}/{sent}/{idx:02d}",
                        "text": text,
                        "label": {"category": cat, "sentiment": sent},
                        "topic": topic,
                    }
                    out_f.write(json.dumps(row, ensure_ascii=False) + "\n")
                    out_f.flush()
                    written += 1
                except Exception as e:
                    failed += 1
                    tqdm.write(f"[ERROR] {cat}/{sent} #{i}: {type(e).__name__}: {e}")
                    if args.strict or failed == 1:
                        tqdm.write(traceback.format_exc())
                    if args.strict:
                        raise
                    time.sleep(1.0)
                pbar.update(1)
            if args.limit is not None and done_count >= args.limit:
                break
    finally:
        pbar.close()
        out_f.close()

    print(f"\nЗаписано: {written}, ошибок: {failed} -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
