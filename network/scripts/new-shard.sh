#!/usr/bin/env bash
# Scaffold shardow trybu (_base + templates/<tryb>). Uzycie: ./new-shard.sh <tryb> [count] [xmx]
set -euo pipefail
cd "$(dirname "$0")/.."
MODE="${1:?Uzyj: ./new-shard.sh <tryb> [count] [xmx]}"
COUNT="${2:-1}"; XMX="${3:-2G}"
# Opcjonalnie: --sector <sx> <sz> [--size <n>] -> shard sektorowy (wspolrzedne w rejestrze).
SECTOR_X=""; SECTOR_Z=""; SECTOR_SIZE=1000
_args=("$@"); _i=0
while [ $_i -lt ${#_args[@]} ]; do
  case "${_args[$_i]}" in
    --sector) SECTOR_X="${_args[$((_i+1))]:-}"; SECTOR_Z="${_args[$((_i+2))]:-}"; _i=$((_i+3)); continue;;
    --size)   SECTOR_SIZE="${_args[$((_i+1))]:-1000}"; _i=$((_i+2)); continue;;
  esac
  _i=$((_i+1))
done
BASE="templates/_base"; MODETPL="templates/$MODE"

find_base() { local rel="$1"; [ -f "$BASE/$rel" ] && { echo "$BASE/$rel"; return; }; for d in servers/*/; do [ -f "$d$rel" ] && { echo "$d$rel"; return; }; done; }
PAPERGLOBAL="$(find_base config/paper-global.yml || true)"
EULA="$(find_base eula.txt || true)"
[ -n "$PAPERGLOBAL" ] && [ -n "$EULA" ] || { echo "Brak bazy (templates/_base lub servers/*). Uruchom setup.sh"; exit 1; }
PAPERJAR="$(ls servers/*/paper.jar 2>/dev/null | head -1 || true)"
[ -n "$PAPERJAR" ] || { [ -f "$BASE/paper.jar" ] && PAPERJAR="$BASE/paper.jar" || true; }
[ -n "$PAPERJAR" ] || { echo "Brak paper.jar - uruchom setup.sh"; exit 1; }
COREJAR="$(ls -t ../mc-core/core-paper/build/libs/core-paper*.jar 2>/dev/null | head -1 || true)"
[ -n "$COREJAR" ] || echo "UWAGA: brak core-paper*.jar (zbuduj mc-core)."

PORTBASE=25600; SOFT=180; HARD=200
if [ -f "$MODETPL/mode.conf" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line#"${line%%[![:space:]]*}"}"; [ -z "$line" ] && continue; [ "${line:0:1}" = "#" ] && continue
    case "$line" in port-base=*) PORTBASE="${line#*=}";; soft-cap=*) SOFT="${line#*=}";; hard-cap=*) HARD="${line#*=}";; esac
  done < "$MODETPL/mode.conf"
else echo "UWAGA: brak $MODETPL/mode.conf - domyslne (port-base 25600)."; fi

used_ports() { grep -hE '^[[:space:]]*server-port' servers/*/server.properties 2>/dev/null | grep -oE '[0-9]+'; }
is_used() { used_ports | grep -qx "$1"; }
free_port() { local p="$1"; while is_used "$p"; do p=$((p+1)); done; echo "$p"; }

maxidx=0
for d in servers/"${MODE}"-*; do [ -d "$d" ] || continue; n="${d##*-}"; { [[ "$n" =~ ^[0-9]+$ ]] && [ "$n" -gt "$maxidx" ] && maxidx="$n"; } || true; done

PORT="$PORTBASE"
for _ in $(seq 1 "$COUNT"); do
  maxidx=$((maxidx + 1)); ID="${MODE}-${maxidx}"; PORT="$(free_port "$PORT")"; DIR="servers/$ID"
  mkdir -p "$DIR/config" "$DIR/plugins"
  cp "$PAPERGLOBAL" "$DIR/config/paper-global.yml"
  cp "$EULA" "$DIR/eula.txt"
  cp "$PAPERJAR" "$DIR/paper.jar"
  [ -n "$COREJAR" ] && cp "$COREJAR" "$DIR/plugins/"
  declare -A P
  P[online-mode]=false; P[server-ip]=127.0.0.1; P[server-port]="$PORT"; P[motd]="El Cartel - $ID"
  P[max-players]="$HARD"; P[view-distance]=8; P[simulation-distance]=6; P[prevent-proxy-connections]=false; P[level-name]=world
  if [ -f "$MODETPL/server.properties.extra" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line#"${line%%[![:space:]]*}"}"; [ -z "$line" ] && continue; [ "${line:0:1}" = "#" ] && continue
      case "$line" in *=*) k="${line%%=*}"; v="${line#*=}"; P["$k"]="$v";; esac
    done < "$MODETPL/server.properties.extra"
  fi
  : > "$DIR/server.properties"; for k in "${!P[@]}"; do echo "$k=${P[$k]}" >> "$DIR/server.properties"; done; unset P
  if [ -d "$MODETPL" ]; then for sub in plugins datapacks config world; do [ -d "$MODETPL/$sub" ] && { mkdir -p "$DIR/$sub"; cp -r "$MODETPL/$sub/." "$DIR/$sub/"; }; done; fi
  SECTOR_EXP=""
  if [ -n "$SECTOR_X" ] && [ -n "$SECTOR_Z" ]; then
    SECTOR_EXP="export ELCARTEL_SECTOR_X=$SECTOR_X
export ELCARTEL_SECTOR_Z=$SECTOR_Z
export ELCARTEL_SECTOR_SIZE=$SECTOR_SIZE
export ELCARTEL_WORLD=world"
  fi
  cat > "$DIR/start.sh" <<S
#!/usr/bin/env bash
cd "\$(dirname "\$0")"
export ELCARTEL_SHARD_ID=$ID
export ELCARTEL_MODE=$MODE
export ELCARTEL_SOFTCAP=$SOFT
export ELCARTEL_HARDCAP=$HARD
export ELCARTEL_ADDR=127.0.0.1:$PORT
$SECTOR_EXP
[ -f paper.jar ] || { echo "Brak paper.jar"; exit 1; }
exec java -Xms$XMX -Xmx$XMX -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+AlwaysPreTouch -XX:G1HeapRegionSize=8M -jar paper.jar --nogui
S
  chmod +x "$DIR/start.sh"
  if [ -n "$SECTOR_X" ] && [ -n "$SECTOR_Z" ]; then
    echo "Utworzono shard $ID (port $PORT, tryb $MODE, SEKTOR $SECTOR_X,$SECTOR_Z rozmiar $SECTOR_SIZE) -> $DIR/start.sh"
  else
    echo "Utworzono shard $ID (port $PORT, tryb $MODE) -> $DIR/start.sh"
  fi
  PORT=$((PORT + 1))
done
echo "Gotowe. Odpal start.sh shardow. Proxy wykryje je z Redisa (~3 s). W grze: /play $MODE"
