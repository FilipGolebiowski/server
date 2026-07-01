# Wspolny LuckPerms (Mongo + Redis) na velocity i shardach.
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)
python configure-luckperms.py @args
