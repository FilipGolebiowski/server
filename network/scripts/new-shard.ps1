# Scaffold shardow trybu z szablonu (_base + templates\<tryb>). Przyklady:
#   .\new-shard.ps1 survival 2
#   .\new-shard.ps1 survival 1 2G --sector 0 0      (to samo co bash)
#   .\new-shard.ps1 survival 1 2G --sector 1 0 --size 1000
param([Parameter(Mandatory=$true)][string]$Mode, [int]$Count = 1, [string]$Xmx = "2G",
      [int[]]$Sector, [int]$Size = 1000)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

# Sektor: jeden parametr -Sector przyjmuje dwie liczby (np. --sector 0 0). Rozbijamy na X/Z.
$SectorX = $null; $SectorZ = $null; $SectorSize = $Size
if ($Sector -and $Sector.Count -ge 2) { $SectorX = [int]$Sector[0]; $SectorZ = [int]$Sector[1] }

$baseTpl = "templates\_base"
$modeTpl = "templates\$Mode"

function Find-Base($rel) {
  $p = Join-Path $baseTpl $rel
  if (Test-Path $p) { return (Get-Item $p).FullName }
  $alt = Get-ChildItem "servers" -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName $rel } | Where-Object { Test-Path $_ } | Select-Object -First 1
  if ($alt) { return (Get-Item $alt).FullName }
  return $null
}
$paperGlobal = Find-Base "config\paper-global.yml"
$eula        = Find-Base "eula.txt"
if (-not $paperGlobal -or -not $eula) { Write-Error "Brak bazy (templates\_base lub servers\*). Uruchom setup.ps1."; exit 1 }
$paperJar = Get-ChildItem "servers\*\paper.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $paperJar -and (Test-Path "$baseTpl\paper.jar")) { $paperJar = Get-Item "$baseTpl\paper.jar" }
if (-not $paperJar) { Write-Error "Brak paper.jar - uruchom setup.ps1."; exit 1 }
$coreJar = Get-ChildItem "..\mc-core\core-paper\build\libs\core-paper*.jar" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $coreJar) { Write-Warning "Nie znaleziono core-paper*.jar - zbuduj mc-core ('gradle build')." }

# mode.conf -> port-base, capy
$conf = @{ "port-base" = "25600"; "soft-cap" = "180"; "hard-cap" = "200" }
$confFile = Join-Path $modeTpl "mode.conf"
if (Test-Path $confFile) {
  foreach ($line in Get-Content $confFile) {
    $t = $line.Trim()
    if ($t -and -not $t.StartsWith("#") -and $t.Contains("=")) { $k = $t.Substring(0, $t.IndexOf("=")).Trim(); $v = $t.Substring($t.IndexOf("=") + 1).Trim(); $conf[$k] = $v }
  }
} else { Write-Warning "Brak $confFile - domyslne (port-base 25600). Dodaj templates\$Mode\mode.conf." }
$portBase = [int]$conf["port-base"]; $soft = [int]$conf["soft-cap"]; $hard = [int]$conf["hard-cap"]

$usedPorts = New-Object System.Collections.ArrayList
Get-ChildItem servers -Directory -ErrorAction SilentlyContinue | ForEach-Object {
  $sp = Join-Path $_.FullName "server.properties"
  if (Test-Path $sp) { $mm = Select-String -Path $sp -Pattern '^\s*server-port\s*=\s*(\d+)'; if ($mm) { [void]$usedPorts.Add([int]$mm.Matches[0].Groups[1].Value) } }
}
function Get-FreePort([int]$from) { $p = $from; while ($usedPorts -contains $p) { $p++ }; [void]$usedPorts.Add($p); return $p }

$existing = Get-ChildItem servers -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match ("^" + [regex]::Escape($Mode) + "-\d+$") } | ForEach-Object { [int]($_.Name.Substring($Mode.Length + 1)) }
$idx = 0; if ($existing) { $idx = ($existing | Measure-Object -Maximum).Maximum }

for ($n = 1; $n -le $Count; $n++) {
  $idx++; $id = "$Mode-$idx"; $port = Get-FreePort $portBase; $dir = "servers\$id"
  New-Item -ItemType Directory -Force -Path (Join-Path $dir "config"), (Join-Path $dir "plugins") | Out-Null
  Copy-Item $paperGlobal (Join-Path $dir "config\paper-global.yml") -Force
  Copy-Item $eula (Join-Path $dir "eula.txt") -Force
  Copy-Item $paperJar.FullName (Join-Path $dir "paper.jar") -Force
  if ($coreJar) { Copy-Item $coreJar.FullName (Join-Path $dir "plugins") -Force }

  $props = [ordered]@{ "online-mode"="false"; "server-ip"="127.0.0.1"; "server-port"="$port"; "motd"="El Cartel - $id"; "max-players"="$hard"; "view-distance"="8"; "simulation-distance"="6"; "prevent-proxy-connections"="false"; "level-name"="world" }
  $extra = Join-Path $modeTpl "server.properties.extra"
  if (Test-Path $extra) { foreach ($line in Get-Content $extra) { $t = $line.Trim(); if ($t -and -not $t.StartsWith("#") -and $t.Contains("=")) { $k = $t.Substring(0, $t.IndexOf("=")); $v = $t.Substring($t.IndexOf("=") + 1); $props[$k] = $v } } }
  Set-Content -Encoding ASCII -Path (Join-Path $dir "server.properties") -Value (($props.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join "`r`n")

  if (Test-Path $modeTpl) { foreach ($sub in "plugins","datapacks","config","world") { $src = Join-Path $modeTpl $sub; if (Test-Path $src) { $dst = Join-Path $dir $sub; New-Item -ItemType Directory -Force -Path $dst | Out-Null; Copy-Item (Join-Path $src "*") $dst -Recurse -Force -ErrorAction SilentlyContinue } } }

  $lines = New-Object System.Collections.ArrayList
  [void]$lines.Add('@echo off'); [void]$lines.Add('cd /d "%~dp0"')
  [void]$lines.Add('set "ELCARTEL_SHARD_ID=' + $id + '"')
  [void]$lines.Add('set "ELCARTEL_MODE=' + $Mode + '"')
  [void]$lines.Add('set "ELCARTEL_SOFTCAP=' + $soft + '"')
  [void]$lines.Add('set "ELCARTEL_HARDCAP=' + $hard + '"')
  [void]$lines.Add('set "ELCARTEL_ADDR=127.0.0.1:' + $port + '"')
  if ($null -ne $SectorX -and $null -ne $SectorZ) {
    [void]$lines.Add('set "ELCARTEL_SECTOR_X=' + $SectorX + '"')
    [void]$lines.Add('set "ELCARTEL_SECTOR_Z=' + $SectorZ + '"')
    [void]$lines.Add('set "ELCARTEL_SECTOR_SIZE=' + $SectorSize + '"')
    [void]$lines.Add('set "ELCARTEL_WORLD=world"')
  }
  [void]$lines.Add('if not exist paper.jar ( echo Brak paper.jar - uruchom setup.ps1 ^& pause ^& exit /b 1 )')
  [void]$lines.Add('java -Xms' + $Xmx + ' -Xmx' + $Xmx + ' -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+AlwaysPreTouch -XX:G1HeapRegionSize=8M -jar paper.jar --nogui')
  [void]$lines.Add('pause')
  Set-Content -Encoding ASCII -Path (Join-Path $dir "start.bat") -Value (($lines) -join "`r`n")
  if ($null -ne $SectorX) { Write-Host ("  -> SEKTOR " + $SectorX + "," + $SectorZ + " (rozmiar " + $SectorSize + ")") }
  Write-Host ("Utworzono shard " + $id + " (port " + $port + ", tryb " + $Mode + ") -> " + $dir + "\start.bat")
}
Write-Host ("Gotowe. Odpal start.bat shardow. Proxy wykryje je z Redisa (~3 s). W grze: /play " + $Mode)
