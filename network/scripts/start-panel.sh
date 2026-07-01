#!/usr/bin/env bash
# El Cartel — panel administracyjny w przeglądarce (FastAPI).
set -euo pipefail
cd "$(dirname "$0")/../panel"

if ! command -v python3 >/dev/null 2>&1; then
  echo "Brak python3 — zainstaluj Python 3.10+"
  exit 1
fi

if [ ! -d ".venv" ]; then
  echo "Tworzenie venv..."
  python3 -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/bin/activate
pip install -q -r requirements.txt

HOST="${ELCARTEL_PANEL_HOST:-127.0.0.1}"
PORT="${ELCARTEL_PANEL_PORT:-8080}"
echo "Panel: http://${HOST}:${PORT}/  (Ctrl+C = stop)"
exec python -m uvicorn app:app --host "$HOST" --port "$PORT"
