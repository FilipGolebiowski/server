@echo off
cd /d "%~dp0"
set "ELCARTEL_SHARD_ID=survival-1"
set "ELCARTEL_MODE=survival"
set "ELCARTEL_SOFTCAP=180"
set "ELCARTEL_HARDCAP=200"
set "ELCARTEL_ADDR=127.0.0.1:25600"
set "ELCARTEL_SECTOR_X=0"
set "ELCARTEL_SECTOR_Z=0"
set "ELCARTEL_SECTOR_SIZE=1000"
set "ELCARTEL_WORLD=world"
if not exist paper.jar ( echo Brak paper.jar - uruchom setup.ps1 ^& pause ^& exit /b 1 )
java -Xms2G -Xmx2G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+AlwaysPreTouch -XX:G1HeapRegionSize=8M -jar paper.jar --nogui
pause
