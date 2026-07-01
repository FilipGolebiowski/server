# El Cartel - panel administracyjny w przegladarce (FastAPI).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path (Split-Path $PSScriptRoot -Parent) "panel")

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
  Write-Error "Brak Pythona - zainstaluj Python 3.10+"
}
$venv = Join-Path $PWD ".venv"
if (-not (Test-Path $venv)) {
  Write-Host "Tworzenie venv..."
  python -m venv $venv
}
& (Join-Path $venv "Scripts\Activate.ps1")
pip install -q -r requirements.txt

$hostAddr = if ($env:ELCARTEL_PANEL_HOST) { $env:ELCARTEL_PANEL_HOST } else { "127.0.0.1" }
$port = if ($env:ELCARTEL_PANEL_PORT) { $env:ELCARTEL_PANEL_PORT } else { "8080" }
Write-Host ('Panel: http://' + $hostAddr + ':' + $port + '/  (Ctrl+C = stop)')
python -m uvicorn app:app --host $hostAddr --port $port
