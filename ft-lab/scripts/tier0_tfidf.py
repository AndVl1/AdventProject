"""День 10 — Tier 0 как TF-IDF + Logistic Regression.

Дешёвый классификатор перед T1 (Qwen2.5-3B). Предсказывает category и sentiment,
возвращает probability. Если max(proba) < threshold → эскалация в T1.

CLI:
    python scripts/tier0_tfidf.py train         # обучить и сохранить модель
    python scripts/tier0_tfidf.py eval          # оценить на valid + eval set
    python scripts/tier0_tfidf.py predict --text "..."
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import joblib
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
MODELS = ROOT / "models"
RESULTS = ROOT / "results"
MODEL_PATH = MODELS / "tier0_tfidf.joblib"

CATEGORIES = ("bug", "feature_request", "billing", "how_to", "other")
SENTIMENTS = ("neg", "neu", "pos")


@dataclass
class Tier0Prediction:
    category: str
    sentiment: str
    cat_proba: float       # max proba по category
    sent_proba: float      # max proba по sentiment
    cat_margin: float      # top1 - top2 по category (более стабильный сигнал)
    sent_margin: float     # top1 - top2 по sentiment
    min_proba: float       # min(cat_proba, sent_proba)
    min_margin: float      # min(cat_margin, sent_margin) — основной сигнал эскалации
    latency_ms: float


def _load_train_jsonl(path: Path) -> tuple[list[str], list[str], list[str]]:
    """Возвращает (texts, categories, sentiments)."""
    texts, cats, sents = [], [], []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            msgs = obj.get("messages") or []
            user_text = ""
            assistant_text = ""
            for m in msgs:
                if m.get("role") == "user":
                    user_text = m.get("content", "")
                elif m.get("role") == "assistant":
                    assistant_text = m.get("content", "")
            if not user_text or not assistant_text:
                continue
            try:
                a = json.loads(assistant_text)
            except Exception:
                continue
            cat = a.get("category")
            sent = a.get("sentiment")
            if cat not in CATEGORIES or sent not in SENTIMENTS:
                continue
            texts.append(user_text)
            cats.append(cat)
            sents.append(sent)
    return texts, cats, sents


def _load_eval_jsonl(path: Path) -> list[dict]:
    """Формат eval (long_cases / edge_cases): {id, user, expected: {category, sentiment}, ...}."""
    items = []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            user = obj.get("user")
            exp = obj.get("expected") or {}
            cat = exp.get("category")
            sent = exp.get("sentiment")
            if not user or cat not in CATEGORIES or sent not in SENTIMENTS:
                continue
            items.append({
                "id": obj.get("id", ""),
                "user": user,
                "category": cat,
                "sentiment": sent,
                "kind": obj.get("kind", "eval"),
            })
    return items


def _load_valid_as_eval(path: Path) -> list[dict]:
    """Из train-формата (messages) делает eval-формат."""
    items = []
    with path.open() as f:
        for i, line in enumerate(f):
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            msgs = obj.get("messages") or []
            user_text = ""
            assistant_text = ""
            for m in msgs:
                if m.get("role") == "user":
                    user_text = m.get("content", "")
                elif m.get("role") == "assistant":
                    assistant_text = m.get("content", "")
            try:
                a = json.loads(assistant_text)
            except Exception:
                continue
            cat = a.get("category")
            sent = a.get("sentiment")
            if cat not in CATEGORIES or sent not in SENTIMENTS:
                continue
            items.append({
                "id": f"valid/{i:02d}",
                "user": user_text,
                "category": cat,
                "sentiment": sent,
                "kind": "valid",
            })
    return items


def _build_pipeline() -> Pipeline:
    """TF-IDF word ngrams 1-2 + LogReg.

    Word ngrams дают более резкие probas чем char_wb (нужно для эскалационного порога).
    class_weight=None — balanced размазывает распределение и ломает margin-сигнал.
    """
    return Pipeline([
        ("tfidf", TfidfVectorizer(
            ngram_range=(1, 2),
            min_df=1,
            max_df=0.95,
            sublinear_tf=True,
            analyzer="word",
            lowercase=True,
        )),
        ("clf", LogisticRegression(
            max_iter=2000,
            C=4.0,
        )),
    ])


def train() -> None:
    train_path = DATA / "train.jsonl"
    valid_path = DATA / "valid.jsonl"
    texts, cats, sents = _load_train_jsonl(train_path)
    vt, vc, vs = _load_train_jsonl(valid_path)
    # train + valid = все размеченные данные (мало — добавляем valid в обучение,
    # отдельный валидационный замер делаем на eval/long_cases + eval/edge_cases).
    all_texts = texts + vt
    all_cats = cats + vc
    all_sents = sents + vs

    print(f"[train] examples: {len(all_texts)} (train {len(texts)} + valid {len(vt)})", file=sys.stderr)
    print(f"[train] cat distribution: {dict((c, all_cats.count(c)) for c in CATEGORIES)}", file=sys.stderr)
    print(f"[train] sent distribution: {dict((s, all_sents.count(s)) for s in SENTIMENTS)}", file=sys.stderr)

    cat_pipe = _build_pipeline()
    sent_pipe = _build_pipeline()
    cat_pipe.fit(all_texts, all_cats)
    sent_pipe.fit(all_texts, all_sents)

    MODELS.mkdir(parents=True, exist_ok=True)
    joblib.dump({
        "cat": cat_pipe,
        "sent": sent_pipe,
        "categories": CATEGORIES,
        "sentiments": SENTIMENTS,
        "n_train": len(all_texts),
    }, MODEL_PATH)
    print(f"[train] saved → {MODEL_PATH}", file=sys.stderr)


def load_model() -> dict:
    if not MODEL_PATH.exists():
        raise SystemExit(f"model not found at {MODEL_PATH}. run: python scripts/tier0_tfidf.py train")
    return joblib.load(MODEL_PATH)


def _top2_margin(proba) -> tuple[int, float, float]:
    """Возвращает (top1_idx, top1_proba, margin = top1 - top2)."""
    sorted_idx = proba.argsort()[::-1]
    top1, top2 = int(sorted_idx[0]), int(sorted_idx[1]) if len(sorted_idx) > 1 else int(sorted_idx[0])
    p1 = float(proba[top1])
    p2 = float(proba[top2]) if top2 != top1 else 0.0
    return top1, p1, p1 - p2


def predict(text: str, model: dict | None = None) -> Tier0Prediction:
    model = model or load_model()
    cat_pipe = model["cat"]
    sent_pipe = model["sent"]
    t0 = time.perf_counter()
    cat_proba = cat_pipe.predict_proba([text])[0]
    sent_proba = sent_pipe.predict_proba([text])[0]
    dt = (time.perf_counter() - t0) * 1000
    cat_idx, cat_max, cat_margin = _top2_margin(cat_proba)
    sent_idx, sent_max, sent_margin = _top2_margin(sent_proba)
    cat_label = cat_pipe.classes_[cat_idx]
    sent_label = sent_pipe.classes_[sent_idx]
    return Tier0Prediction(
        category=str(cat_label),
        sentiment=str(sent_label),
        cat_proba=cat_max,
        sent_proba=sent_max,
        cat_margin=cat_margin,
        sent_margin=sent_margin,
        min_proba=min(cat_max, sent_max),
        min_margin=min(cat_margin, sent_margin),
        latency_ms=round(dt, 2),
    )


def evaluate() -> None:
    """T0-only прогон на 30 примерах (10 valid + 10 long + 10 edge_cases)."""
    model = load_model()
    valid = _load_valid_as_eval(DATA / "valid.jsonl")[:10]
    long = _load_eval_jsonl(DATA / "eval" / "long_cases.jsonl")[:10]
    edge = _load_eval_jsonl(DATA / "eval" / "edge_cases.jsonl")[:10]
    items = valid + long + edge
    print(f"[eval] {len(items)} items (valid={len(valid)} long={len(long)} edge={len(edge)})", file=sys.stderr)

    rows = []
    cat_correct = sent_correct = both_correct = 0
    total_lat = 0.0
    for it in items:
        pred = predict(it["user"], model)
        cat_ok = pred.category == it["category"]
        sent_ok = pred.sentiment == it["sentiment"]
        cat_correct += int(cat_ok)
        sent_correct += int(sent_ok)
        both_correct += int(cat_ok and sent_ok)
        total_lat += pred.latency_ms
        rows.append({
            "id": it["id"],
            "kind": it["kind"],
            "expected": {"category": it["category"], "sentiment": it["sentiment"]},
            "pred": {"category": pred.category, "sentiment": pred.sentiment},
            "cat_proba": round(pred.cat_proba, 3),
            "sent_proba": round(pred.sent_proba, 3),
            "cat_margin": round(pred.cat_margin, 3),
            "sent_margin": round(pred.sent_margin, 3),
            "min_proba": round(pred.min_proba, 3),
            "min_margin": round(pred.min_margin, 3),
            "match": {"category": cat_ok, "sentiment": sent_ok, "all": cat_ok and sent_ok},
            "latency_ms": pred.latency_ms,
        })

    n = len(items)
    summary = {
        "n": n,
        "acc_category": round(100 * cat_correct / n, 1),
        "acc_sentiment": round(100 * sent_correct / n, 1),
        "acc_all": round(100 * both_correct / n, 1),
        "avg_latency_ms": round(total_lat / n, 2),
    }
    RESULTS.mkdir(parents=True, exist_ok=True)
    out_jsonl = RESULTS / "day10-tier0-only.jsonl"
    with out_jsonl.open("w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    print("\n## Tier 0 — TF-IDF + LogReg (T0 only, на всех 30)\n")
    print(f"- Examples: **{n}**")
    print(f"- acc category : **{summary['acc_category']}%**")
    print(f"- acc sentiment: **{summary['acc_sentiment']}%**")
    print(f"- acc both     : **{summary['acc_all']}%**")
    print(f"- avg latency  : **{summary['avg_latency_ms']} ms**")
    print()
    print("## Margin-thresholds ablation (precision@confident)\n")
    print("| threshold | confident | acc@confident | escalated |")
    print("|---|---|---|---|")
    for thr in (0.05, 0.10, 0.15, 0.20, 0.30, 0.50):
        confident = [r for r in rows if r["min_margin"] >= thr]
        ok = sum(1 for r in confident if r["match"]["all"])
        nc = len(confident)
        acc_conf = round(100 * ok / nc, 1) if nc else 0.0
        print(f"| {thr:.2f} | {nc} | {acc_conf}% ({ok}/{nc}) | {n - nc} |")
    print(f"\nDetails → `{out_jsonl.relative_to(ROOT)}`")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("cmd", choices=["train", "eval", "predict"])
    p.add_argument("--text", help="для predict")
    args = p.parse_args()
    if args.cmd == "train":
        train()
    elif args.cmd == "eval":
        evaluate()
    elif args.cmd == "predict":
        if not args.text:
            raise SystemExit("--text required for predict")
        pred = predict(args.text)
        print(json.dumps(pred.__dict__, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
