#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
PAPER="$(ls -t ../mc-core/core-paper/build/libs/core-paper*.jar 2>/dev/null | head -1 || true)"
VELO="$(ls -t ../mc-core/core-velocity/build/libs/core-velocity*.jar 2>/dev/null | head -1 || true)"
[ -n "$PAPER" ] && [ -n "$VELO" ] || { echo "Brak jarow core - zbuduj mc-core (gradle build)."; exit 1; }
mkdir -p velocity/plugins; rm -f velocity/plugins/core-velocity*.jar; cp "$VELO" velocity/plugins/; echo "core-velocity -> velocity/plugins"
for d in servers/*/; do mkdir -p "$d/plugins"; rm -f "$d"plugins/core-paper*.jar; cp "$PAPER" "$d/plugins/"; echo "core-paper -> ${d}plugins"; done
# pluginy trybow
for moddir in ../mc-core/mode-*/; do
  [ -d "$moddir" ] || continue
  mod="$(basename "$moddir")"; mode="${mod#mode-}"
  jar="$(ls -t "$moddir"build/libs/"$mod"*.jar 2>/dev/null | head -1 || true)"
  [ -n "$jar" ] || { echo "Brak jara $mod (zbuduj) - pomijam."; continue; }
  if [ -d "templates/$mode" ]; then mkdir -p "templates/$mode/plugins"; rm -f "templates/$mode/plugins/$mod"*.jar; cp "$jar" "templates/$mode/plugins/"; echo "$mod -> templates/$mode/plugins"; fi
  for d in servers/"$mode"-*/; do [ -d "$d" ] || continue; mkdir -p "$d/plugins"; rm -f "$d"plugins/"$mod"*.jar; cp "$jar" "$d/plugins/"; echo "$mod -> ${d}plugins"; done
done
echo "Gotowe. Zrestartuj dzialajace serwery."
