@echo off
cd /d "%~dp0"
if not exist velocity.jar (
  echo Brak velocity.jar - uruchom najpierw setup.ps1
  pause
  exit /b 1
)
java -Xms1G -Xmx2G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+AlwaysPreTouch -XX:G1HeapRegionSize=4M -jar velocity.jar
pause
