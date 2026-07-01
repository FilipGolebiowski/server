@echo off
cd /d "%~dp0"
rem rola limbo wlacza auth-gate; sekrety czyta core z ..\..\elcartel.properties
set "ELCARTEL_ROLE=limbo"
if not exist paper.jar (
  echo Brak paper.jar - uruchom najpierw ..\..\setup.ps1
  pause
  exit /b 1
)
java -Xms1G -Xmx1G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+AlwaysPreTouch -XX:G1HeapRegionSize=4M -jar paper.jar --nogui
pause
