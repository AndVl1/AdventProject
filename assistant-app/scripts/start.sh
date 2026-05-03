#!/usr/bin/env bash
# Поднимает backend (Spring Boot) + frontend (Vite dev server) в фоне.
# Логи: assistant-app/backend.log, frontend.log. PID: .pids/{backend,frontend}.pid
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_DIR="$ROOT/.pids"
mkdir -p "$PID_DIR"

# Подтянуть .env, если есть, чтобы Spring и оболочка видели ключи
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
  echo "[start] подгружен $ROOT/.env"
else
  echo "[start] WARN: $ROOT/.env не найден — Gmail/OpenRouter тулы вернут ошибку"
fi

stop_existing() {
  for name in backend frontend; do
    local pidf="$PID_DIR/$name.pid"
    if [ -f "$pidf" ] && kill -0 "$(cat "$pidf")" 2>/dev/null; then
      echo "[start] останавливаю старый $name (pid=$(cat "$pidf"))"
      kill "$(cat "$pidf")" 2>/dev/null || true
      sleep 1
    fi
    rm -f "$pidf"
  done
}

stop_existing

# ── Backend ────────────────────────────────────────────────────────────────
echo "[start] backend → $ROOT/backend.log"
cd "$ROOT/backend"
nohup ./mvnw -q spring-boot:run > "$ROOT/backend.log" 2>&1 &
echo $! > "$PID_DIR/backend.pid"

# ── Frontend ───────────────────────────────────────────────────────────────
echo "[start] frontend → $ROOT/frontend.log"
cd "$ROOT/frontend"
if [ ! -d node_modules ]; then
  echo "[start] первая установка npm зависимостей..."
  npm install
fi
nohup npm run dev > "$ROOT/frontend.log" 2>&1 &
echo $! > "$PID_DIR/frontend.pid"

cat <<EOF

[start] оба процесса запущены.
  backend  pid $(cat "$PID_DIR/backend.pid")  log $ROOT/backend.log   http://localhost:8090
  frontend pid $(cat "$PID_DIR/frontend.pid") log $ROOT/frontend.log  http://localhost:5173

ожидание готовности backend...
EOF

# Жди до 60 секунд, пока backend ответит на /api/tools
for i in $(seq 1 60); do
  if curl -sf -o /dev/null http://localhost:8090/api/tools; then
    echo "[start] backend готов (через ${i}с)"
    break
  fi
  if [ "$i" = 60 ]; then
    echo "[start] WARN: backend не отвечает за 60с — смотри $ROOT/backend.log"
  fi
  sleep 1
done

echo "[start] остановить: ./scripts/stop.sh"
