#!/usr/bin/env bash
# Limbo (minimalny Paper) - rola limbo wlacza auth-gate; config: ../../elcartel.properties
cd "$(dirname "$0")"
export ELCARTEL_ROLE=limbo
if [ ! -f paper.jar ]; then
  echo "Brak paper.jar — uruchom najpierw ../../setup.sh (Linux) lub ../../setup.ps1 (Windows)."
  exit 1
fi
exec java -Xms1G -Xmx1G \
 -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
 -XX:+UnlockExperimentalVMOptions -XX:+AlwaysPreTouch -XX:G1HeapRegionSize=4M \
 -jar paper.jar --nogui
