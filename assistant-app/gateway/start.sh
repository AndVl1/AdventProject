#!/usr/bin/env bash
# Запуск LLM Gateway локально.
#   ./start.sh           — foreground
#   ./start.sh -d        — daemon (nohup, лог в /tmp/gateway.log, pid в .gateway.pid)
#   ./start.sh stop      — убить демона
#   ./start.sh restart   — stop + start -d
#   ./start.sh status    — порт + pid

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PORT="${GATEWAY_PORT:-8091}"
LOG="${GATEWAY_LOG:-/tmp/gateway.log}"
PID_FILE="$SCRIPT_DIR/.gateway.pid"
ENV_FILE="$SCRIPT_DIR/../.env"
MVNW="$SCRIPT_DIR/../backend/mvnw"

# JVM ограничения для slim-окружения (VDS / dev-mac):
# без -Xmx JVM берёт MaxRAMPercentage=25% от RAM (на 24G маке = 6G heap, на 32G = 8G).
# Реальный footprint гейтвея — 200-500MB. 768MB heap с запасом.
# +ExitOnOutOfMemoryError — упасть честно вместо OOM-thrashing.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} \
-Xms128m \
-Xmx768m \
-XX:MaxMetaspaceSize=256m \
-XX:+ExitOnOutOfMemoryError \
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=200"

cmd_status() {
  if lsof -iTCP:"$PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    local pid
    pid="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN -n -P | head -1)"
    echo "running on :$PORT (pid $pid)"
    return 0
  fi
  echo "not running on :$PORT"
  return 1
}

cmd_stop() {
  local pid=""
  [[ -f "$PID_FILE" ]] && pid="$(cat "$PID_FILE")"
  [[ -z "$pid" ]] && pid="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN -n -P 2>/dev/null | head -1 || true)"
  if [[ -z "$pid" ]]; then
    echo "nothing to stop on :$PORT"
    rm -f "$PID_FILE"
    return 0
  fi
  echo "stopping pid $pid ..."
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 20); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.25
  done
  kill -0 "$pid" 2>/dev/null && { echo "force-kill"; kill -9 "$pid" || true; }
  rm -f "$PID_FILE"
  echo "stopped"
}

ensure_env() {
  if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
  else
    echo "warn: $ENV_FILE not found — OPENROUTER_API_KEY будет пустым" >&2
  fi
  if [[ -z "${OPENROUTER_API_KEY:-}" ]]; then
    echo "warn: OPENROUTER_API_KEY пуст — upstream вызовы дадут 401" >&2
  fi
}

ensure_port_free() {
  if lsof -iTCP:"$PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    echo "error: port $PORT занят. ./start.sh stop или GATEWAY_PORT=xxxx ./start.sh" >&2
    exit 1
  fi
}

build_if_needed() {
  if [[ ! -d target/classes ]]; then
    echo "first build ..."
    "$MVNW" -q -DskipTests package
  fi
}

cmd_start_fg() {
  ensure_env
  ensure_port_free
  build_if_needed
  echo "→ foreground :$PORT  (Ctrl-C для выхода)"
  exec "$MVNW" spring-boot:run
}

cmd_start_bg() {
  ensure_env
  ensure_port_free
  build_if_needed
  echo "→ daemon :$PORT  log=$LOG  pid=$PID_FILE"
  : > "$LOG"
  nohup "$MVNW" spring-boot:run >>"$LOG" 2>&1 &
  echo $! > "$PID_FILE"
  for _ in $(seq 1 60); do
    if lsof -iTCP:"$PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
      echo "ready on :$PORT (pid $(cat "$PID_FILE"))"
      return 0
    fi
    sleep 1
  done
  echo "error: не поднялся за 60s, см. $LOG" >&2
  tail -30 "$LOG" >&2
  exit 1
}

case "${1:-}" in
  ""|fg|start)        cmd_start_fg ;;
  -d|bg|daemon)       cmd_start_bg ;;
  stop)               cmd_stop ;;
  restart)            cmd_stop; cmd_start_bg ;;
  status)             cmd_status ;;
  logs)               tail -f "$LOG" ;;
  *)                  echo "usage: $0 [start|-d|stop|restart|status|logs]" >&2; exit 2 ;;
esac
