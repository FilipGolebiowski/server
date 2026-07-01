param (
    [Parameter(Mandatory=$true)]
    [string]$ServerName
)

$ErrorActionPreference = "Stop"

$NetworkDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ServersDir = Join-Path $NetworkDir "servers"
$TemplatesDir = Join-Path $NetworkDir "templates"
$SourceDir = Join-Path $ServersDir $ServerName

if (-not (Test-Path $SourceDir)) {
    Write-Host "BLAD: Serwer '$ServerName' nie istnieje." -ForegroundColor Red
    exit 1
}

$TemplateName = $ServerName -replace '-[0-9]+$', ''
if ($TemplateName -eq $ServerName) {
    Write-Host "BLAD: Nazwa serwera musi miec format <tryb>-<numer>." -ForegroundColor Red
    exit 1
}

$DestDir = Join-Path $TemplatesDir $TemplateName
if (-not (Test-Path $DestDir)) {
    Write-Host "BLAD: Szablon '$TemplateName' nie istnieje." -ForegroundColor Red
    exit 1
}

$PluginsSource = Join-Path $SourceDir "plugins"
$PluginsDest = Join-Path $DestDir "plugins"

Write-Host "Rozpoczynam wsteczny Back-Sync z $ServerName do szablonu $TemplateName..." -ForegroundColor Cyan

$ExclFiles = @("*.jar", "*.db", "*.sqlite", "*.mv.db", "*.db-wal", "*.db-shm", "*.log", "*.lck", "*.pid")
$ExclDirs = @("bStats", "logs", "cache", ".paper-remapped", "update")

$RoboArgs = @($PluginsSource, $PluginsDest, "/E", "/NJH", "/NJS", "/NP", "/NDL", "/XF") + $ExclFiles + @("/XD") + $ExclDirs
$proc = Start-Process -FilePath "robocopy.exe" -ArgumentList $RoboArgs -Wait -NoNewWindow -PassThru

if ($proc.ExitCode -ge 8) {
    Write-Host "Wystapil blad podczas kopiowania. Kod: $($proc.ExitCode)" -ForegroundColor Red
} else {
    Write-Host "SUKCES! Zsynchronizowano pliki do szablonu '$TemplateName'." -ForegroundColor Green
}
