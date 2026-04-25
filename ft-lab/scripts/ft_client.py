"""Клиент для запуска fine-tuning через mlx-lm + конверт в GGUF.

Локальный аналог "OpenAI fine-tuning API" под Apple Silicon.

Subcommands:
    prepare    конверт data/train.jsonl + valid.jsonl в формат mlx-lm
    train      запуск LoRA SFT через mlx_lm.lora
    status     парс результатов из results/train.log
    merge      слить LoRA-адаптеры в base, выгрузить HF-формат
    to-gguf    конверт HF в GGUF + квантизация Q4_K_M
    serve      llama-server на FT-результате (порт 8082)

По заданию дня 6 — НЕ запускать train. Скрипт пишется и smoke-тестируется.
Для отладки используй --dry-run (печатает команды, не выполняет).
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
MLX_DATA = DATA / "mlx"
ADAPTERS = ROOT / "adapters"
MERGED = ROOT / "models" / "ft-merged"
GGUF_F16 = ROOT / "models" / "ft.gguf"
GGUF_Q4 = ROOT / "models" / "ft-q4km.gguf"
LOG = ROOT / "results" / "train.log"
METRICS = ROOT / "results" / "train_metrics.jsonl"

DEFAULT_BASE = "mlx-community/Qwen2.5-3B-Instruct-bf16"
DEFAULT_ITERS = 600
DEFAULT_BATCH = 4
DEFAULT_LORA_LAYERS = 16
DEFAULT_LR = 1e-5

# путь к llama.cpp (для convert_hf_to_gguf.py и llama-quantize)
LLAMA_CPP_DIR = Path(os.getenv("LLAMA_CPP_DIR", str(Path.home() / "llama.cpp")))


def run(cmd: list[str], *, dry_run: bool, log_path: Path | None = None) -> int:
    pretty = " ".join(shlex.quote(c) for c in cmd)
    print(f"$ {pretty}")
    if dry_run:
        return 0
    if log_path:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("a", encoding="utf-8") as f:
            f.write(f"\n$ {pretty}\n")
            return subprocess.call(cmd, stdout=f, stderr=subprocess.STDOUT)
    return subprocess.call(cmd)


# --- subcommands ---

def cmd_prepare(args) -> int:
    """mlx-lm ожидает train.jsonl + valid.jsonl с ключом 'messages' в data dir."""
    src_train = DATA / "train.jsonl"
    src_valid = DATA / "valid.jsonl"
    if not src_train.exists() or not src_valid.exists():
        print("Сначала запусти build_dataset.py", file=sys.stderr)
        return 1
    MLX_DATA.mkdir(parents=True, exist_ok=True)
    shutil.copy(src_train, MLX_DATA / "train.jsonl")
    shutil.copy(src_valid, MLX_DATA / "valid.jsonl")
    n_train = sum(1 for _ in (MLX_DATA / "train.jsonl").open())
    n_valid = sum(1 for _ in (MLX_DATA / "valid.jsonl").open())
    print(f"OK: {MLX_DATA}/train.jsonl ({n_train}), valid.jsonl ({n_valid})")
    return 0


def cmd_train(args) -> int:
    if not (MLX_DATA / "train.jsonl").exists():
        print("Запусти `ft_client.py prepare` сначала", file=sys.stderr)
        return 1
    ADAPTERS.mkdir(parents=True, exist_ok=True)
    LOG.parent.mkdir(parents=True, exist_ok=True)

    cmd = [
        sys.executable, "-m", "mlx_lm.lora",
        "--model", args.base,
        "--train",
        "--data", str(MLX_DATA),
        "--iters", str(args.iters),
        "--batch-size", str(args.batch_size),
        "--num-layers", str(args.lora_layers),
        "--learning-rate", str(args.lr),
        "--adapter-path", str(ADAPTERS),
        "--save-every", "100",
        "--steps-per-eval", "50",
    ]
    return run(cmd, dry_run=args.dry_run, log_path=LOG)


def cmd_status(args) -> int:
    """Парс train.log: train/val loss по итерациям."""
    if not LOG.exists():
        print(f"Нет лога: {LOG}", file=sys.stderr)
        return 1
    text = LOG.read_text(encoding="utf-8")
    train_lines = [l for l in text.splitlines() if "Iter" in l and "Train loss" in l]
    val_lines = [l for l in text.splitlines() if "Iter" in l and "Val loss" in l]
    print(f"Train log: {LOG}  ({len(text):.0f} chars)")
    print(f"Train iterations logged: {len(train_lines)}")
    print(f"Val checkpoints: {len(val_lines)}")
    if train_lines:
        print("\nПоследние 5 train записей:")
        for line in train_lines[-5:]:
            print(f"  {line.strip()}")
    if val_lines:
        print("\nПоследние 5 val записей:")
        for line in val_lines[-5:]:
            print(f"  {line.strip()}")
    return 0


def cmd_merge(args) -> int:
    if not ADAPTERS.exists() or not any(ADAPTERS.iterdir()):
        print("Нет адаптеров — сначала запусти `train`", file=sys.stderr)
        return 1
    MERGED.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        sys.executable, "-m", "mlx_lm.fuse",
        "--model", args.base,
        "--adapter-path", str(ADAPTERS),
        "--save-path", str(MERGED),
    ]
    return run(cmd, dry_run=args.dry_run)


def cmd_to_gguf(args) -> int:
    if not MERGED.exists():
        print("Нет merged модели — сначала запусти `merge`", file=sys.stderr)
        return 1
    convert_script = LLAMA_CPP_DIR / "convert_hf_to_gguf.py"
    quantize_bin = LLAMA_CPP_DIR / "build" / "bin" / "llama-quantize"
    if not convert_script.exists():
        print(f"Не найден {convert_script} — установи LLAMA_CPP_DIR", file=sys.stderr)
        return 1
    if not quantize_bin.exists():
        # попробовать без build/
        alt = LLAMA_CPP_DIR / "llama-quantize"
        if alt.exists():
            quantize_bin = alt
        else:
            print(f"Не найден llama-quantize в {LLAMA_CPP_DIR}", file=sys.stderr)
            return 1

    GGUF_F16.parent.mkdir(parents=True, exist_ok=True)
    rc = run([
        sys.executable, str(convert_script),
        str(MERGED),
        "--outfile", str(GGUF_F16),
        "--outtype", "f16",
    ], dry_run=args.dry_run)
    if rc:
        return rc

    return run([
        str(quantize_bin),
        str(GGUF_F16),
        str(GGUF_Q4),
        "Q4_K_M",
    ], dry_run=args.dry_run)


def cmd_serve(args) -> int:
    if not GGUF_Q4.exists() and not args.dry_run:
        print(f"Нет {GGUF_Q4} — пройди merge + to-gguf", file=sys.stderr)
        return 1
    cmd = [
        "llama-server",
        "-m", str(GGUF_Q4),
        "--host", "127.0.0.1",
        "--port", str(args.port),
        "--n-gpu-layers", "999",
        "--ctx-size", "4096",
        "--jinja",
    ]
    return run(cmd, dry_run=args.dry_run)


# --- entrypoint ---

def main() -> int:
    parser = argparse.ArgumentParser(description="FT клиент через mlx-lm")
    parser.add_argument("--dry-run", action="store_true", help="печать команд без выполнения")
    sub = parser.add_subparsers(dest="action", required=True)

    sub.add_parser("prepare", help="конверт датасета в mlx-lm формат")

    p_train = sub.add_parser("train", help="LoRA SFT через mlx_lm.lora")
    p_train.add_argument("--base", default=DEFAULT_BASE)
    p_train.add_argument("--iters", type=int, default=DEFAULT_ITERS)
    p_train.add_argument("--batch-size", type=int, default=DEFAULT_BATCH)
    p_train.add_argument("--lora-layers", type=int, default=DEFAULT_LORA_LAYERS)
    p_train.add_argument("--lr", type=float, default=DEFAULT_LR)

    sub.add_parser("status", help="парс train.log")

    p_merge = sub.add_parser("merge", help="слить LoRA в base")
    p_merge.add_argument("--base", default=DEFAULT_BASE)

    sub.add_parser("to-gguf", help="конверт HF -> GGUF Q4_K_M")

    p_serve = sub.add_parser("serve", help="llama-server на FT-модели")
    p_serve.add_argument("--port", type=int, default=8082)

    args = parser.parse_args()

    handler = {
        "prepare": cmd_prepare,
        "train": cmd_train,
        "status": cmd_status,
        "merge": cmd_merge,
        "to-gguf": cmd_to_gguf,
        "serve": cmd_serve,
    }[args.action]
    return handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
