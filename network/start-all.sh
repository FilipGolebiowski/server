#!/usr/bin/env bash
# Startuje siec w tle: limbo -> shardy -> velocity. Logi w network/.logs/.
set -euo pipefail
cd "$(dirname "$0")"
LOGDIR="$(pwd)/.logs"; mkdir -p "$LOGDIR"
launch(){ local dir="$1"; ( cd "$dir" && nohup bash start.sh > "$LOGDIR/$(basename "$dir").log" 2>&1 & ) ; }
[ -f servers/limbo/start.sh ] && { echo "limbo..."; launch servers/limbo; sleep 8; }
for d in servers/*/; do b="${d%/}"; [ "$(basename "$b")" = "limbo" ] && continue; [ -f "$b/start.sh" ] && { echo "$(basename "$b")..."; launch "$b"; sleep 5; }; done
[ -f velocity/start.sh ] && { echo "velocity..."; launch velocity; }
echo "Wystartowano w tle. Logi: network/.logs/. Stop: pkill -f 'paper.jar|velocity.jar'"
