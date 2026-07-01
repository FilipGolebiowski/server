# Startuje cala siec: limbo -> shardy (servers\*) -> velocity. Kazdy w osobnym oknie.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
function Launch($rel) { $full = (Resolve-Path $rel).Path; Start-Process -FilePath $full -WorkingDirectory (Split-Path $full) }

if (Test-Path "servers\limbo\start.bat") { Write-Host "Start limbo..."; Launch "servers\limbo\start.bat"; Start-Sleep -Seconds 8 }
Get-ChildItem servers -Directory | Where-Object { $_.Name -ne "limbo" } | ForEach-Object {
  $b = Join-Path $_.FullName "start.bat"
  if (Test-Path $b) { Write-Host ("Start " + $_.Name + "..."); Launch $b; Start-Sleep -Seconds 5 }
}
if (Test-Path "velocity\start.bat") { Write-Host "Start velocity..."; Launch "velocity\start.bat" }
Write-Host "Wystartowano (kazdy w osobnym oknie): limbo -> shardy -> velocity."
