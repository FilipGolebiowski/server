#!/usr/bin/env bash
# Velocity proxy (config: ../elcartel.properties, czytany przez plugin)
cd "$(dirname "$0")"
if [ ! -f velocity.jar ]; then
  echo "Brak velocity.jar — uruchom najpierw ../setup.sh (Linux) lub ../setup.ps1 (Windows)."
  exit 1
fi
exec java -Xms1G -Xmx2G \
  -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
  -XX:+UnlockExperimentalVMOptions -XX:+AlwaysPreTouch -XX:G1HeapRegionSize=4M \
  -jar velocity.jar
