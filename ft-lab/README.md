# ft-lab

Локальный fine-tuning эксперимент для дней 6–10 челленджа.

## Стек

- macOS / Apple Silicon (M-series)
- Python 3.11+
- `llama.cpp` с `llama-server`
- `mlx-lm` (для дня 6 FT)
- `sentence-transformers` (для дня 10)

## Модели

| Назначение | Файл / репо | Размер |
|------------|-------------|--------|
| Tier 2 / teacher | `unsloth/Qwen3-30B-A3B-GGUF:UD-Q3_K_S` | ~14 GB |
| FT-base | `mlx-community/Qwen2.5-3B-Instruct-bf16` | ~6 GB |
| FT-result (Tier 1) | `models/ft-q4km.gguf` (после дня 6) | ~2 GB |

## Старт

```bash
cd ft-lab
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env

# запуск teacher (Qwen3-30B-A3B)
llama-server -m /path/to/Qwen3-30B-A3B-UD-Q3_K_S.gguf \
  --host 127.0.0.1 --port 8081 --n-gpu-layers 999 --ctx-size 8192

# (после скачивания) запуск base 3B для baseline
# скачать GGUF Qwen2.5-3B-Instruct Q4_K_M либо сконвертировать сам через convert_hf_to_gguf.py
llama-server -m /path/to/Qwen2.5-3B-Instruct-Q4_K_M.gguf \
  --host 127.0.0.1 --port 8080 --n-gpu-layers 999 --ctx-size 4096
```

## Пайплайн дня 6

```bash
python scripts/scrape_github.py        # реальные тикеты -> data/raw/github_issues.jsonl
# вручную: разметить data/raw/github_issues.jsonl -> data/raw/labeled.jsonl
python scripts/gen_synthetic.py        # синтетика через teacher -> data/synthetic/synthetic.jsonl
python scripts/build_dataset.py        # merge + dedupe + split -> data/train.jsonl, data/valid.jsonl
python scripts/validate.py             # проверка JSONL
python scripts/baseline.py             # baseline на 10 примерах -> results/baseline.jsonl
python scripts/ft_client.py prepare    # подготовка для mlx-lm
```
