# Kopiuje zbudowane jary core + pluginy trybow do sieci. Uruchom po 'gradle build' w mc-core.
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)
$paper = Get-ChildItem "..\mc-core\core-paper\build\libs\core-paper*.jar" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
$velo  = Get-ChildItem "..\mc-core\core-velocity\build\libs\core-velocity*.jar" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $paper -or -not $velo) { Write-Error "Brak jarow core - zbuduj najpierw mc-core ('gradle build')."; exit 1 }

New-Item -ItemType Directory -Force -Path "velocity\plugins" | Out-Null
Get-ChildItem "velocity\plugins\core-velocity*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item $velo.FullName "velocity\plugins\" -Force
Write-Host "core-velocity -> velocity\plugins"

Get-ChildItem servers -Directory -ErrorAction SilentlyContinue | ForEach-Object {
  $pl = Join-Path $_.FullName "plugins"; New-Item -ItemType Directory -Force -Path $pl | Out-Null
  Get-ChildItem (Join-Path $pl "core-paper*.jar") -ErrorAction SilentlyContinue | Remove-Item -Force
  Copy-Item $paper.FullName $pl -Force
  Write-Host ("core-paper   -> servers\" + $_.Name + "\plugins")
}

# pluginy trybow: mode-<m>.jar -> templates\<m>\plugins + servers\<m>-*\plugins
Get-ChildItem "..\mc-core" -Directory -Filter "mode-*" -ErrorAction SilentlyContinue | ForEach-Object {
  $mod = $_.Name; $mode = $mod -replace '^mode-', ''
  $jar = Get-ChildItem (Join-Path $_.FullName "build\libs\$mod*.jar") -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
  if (-not $jar) { Write-Warning "Brak jara $mod (zbuduj) - pomijam."; return }
  if (Test-Path "templates\$mode") {
    $tpl = "templates\$mode\plugins"; New-Item -ItemType Directory -Force -Path $tpl | Out-Null
    Get-ChildItem (Join-Path $tpl "$mod*.jar") -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $jar.FullName $tpl -Force; Write-Host ("$mod -> $tpl")
  }
  Get-ChildItem servers -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match "^$mode-\d+$" } | ForEach-Object {
    $pl = Join-Path $_.FullName "plugins"; New-Item -ItemType Directory -Force -Path $pl | Out-Null
    Get-ChildItem (Join-Path $pl "$mod*.jar") -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $jar.FullName $pl -Force; Write-Host ("$mod -> servers\" + $_.Name + "\plugins")
  }
}
Write-Host "Gotowe. Zrestartuj dzialajace serwery."
