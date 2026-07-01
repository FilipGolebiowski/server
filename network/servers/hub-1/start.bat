@echo off
cd /d "%~dp0"
set "ELCARTEL_SHARD_ID=hub-1"
set "ELCARTEL_MODE=hub"
set "ELCARTEL_SOFTCAP=200"
set "ELCARTEL_HARDCAP=300"
set "ELCARTEL_ADDR=127.0.0.1:25500"
if not exist paper.jar ( echo Brak paper.jar - uruchom setup.ps1 ^& pause ^& exit /b 1 )
java -Xms2G -Xmx2G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+AlwaysPreTouch -XX:G1HeapRegionSize=8M -jar paper.jar --nogui
pause
