#!/usr/bin/env bash
# Wsteczny sync konfigow pluginow z dzialajacego sharda do templates/<tryb>/plugins/.
# Uzycie: ./save-template.sh <tryb>-<numer>   np. ./save-template.sh survival-1
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER="${1:?Uzyj: ./save-template.sh <tryb>-<numer>  np. survival-1}"
SRC="servers/$SERVER/plugins"

if [[ ! "$SERVER" =~ ^(.+)-[0-9]+$ ]]; then
  echo "BLAD: Nazwa serwera musi miec format <tryb>-<numer>." >&2
  exit 1
fi
MODE="${BASH_REMATCH[1]}"
DEST="templates/$MODE/plugins"

if [[ ! -d "servers/$SERVER" ]]; then
  echo "BLAD: Serwer '$SERVER' nie istnieje." >&2
  exit 1
fi
if [[ ! -d "templates/$MODE" ]]; then
  echo "BLAD: Szablon '$MODE' nie istnieje." >&2
  exit 1
fi
if [[ ! -d "$SRC" ]]; then
  echo "BLAD: Brak katalogu $SRC" >&2
  exit 1
fi

mkdir -p "$DEST"
echo "Back-sync: $SERVER -> templates/$MODE/plugins ..."

rsync -a \
  --exclude='*.jar' \
  --exclude='*.db' \
  --exclude='*.sqlite' \
  --exclude='*.mv.db' \
  --exclude='*.db-wal' \
  --exclude='*.db-shm' \
  --exclude='*.log' \
  --exclude='*.lck' \
  --exclude='*.pid' \
  --exclude='bStats/' \
  --exclude='logs/' \
  --exclude='cache/' \
  --exclude='.paper-remapped/' \
  --exclude='update/' \
  "$SRC/" "$DEST/"

echo "SUKCES! Zsynchronizowano pluginy do szablonu '$MODE'."
