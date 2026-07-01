# Pobiera jary przez PaperMC Fill API (v3 - fill.papermc.io). Windows PowerShell.
#   - Paper 1.21.11 (najnowszy STABLE build) -> do KAZDEGO folderu w servers\
#     (lobby, limbo, survival oraz dowolne dodane tryby)
#   - najnowszy Velocity -> velocity\velocity.jar
#
# Uwaga: Fill API wymaga NIEGENERYCZNEGO User-Agent. Stare API v2 zostalo wylaczone.
# Uruchom:  powershell -ExecutionPolicy Bypass -File setup.ps1
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # bez tego Invoke-WebRequest w PS 5.1 pobiera bardzo wolno
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12  # PS 5.1 domyslnie nie wlacza TLS 1.2
Set-Location $PSScriptRoot

$PaperVer = "1.21.11"
# Mozesz podmienic kontakt ponizej na wlasny (URL/email) - byle nie generyczny.
$UA  = "elcartelgg-network-setup/1.0 (+https://elcartel.gg)"
$API = "https://fill.papermc.io/v3"
$hdr = @{ "User-Agent" = $UA }

function Get-JarUrl($builds) {
  $stable = @($builds | Where-Object { "$($_.channel)".ToUpper() -eq "STABLE" })
  $pool = if ($stable.Count) { $stable } else { @($builds) }
  if (-not $pool.Count) { return $null }
  $b = $pool | Sort-Object { [int]$_.id } -Descending | Select-Object -First 1
  $dl = $b.downloads.'server:default'
  if (-not $dl) { $dl = ($b.downloads.PSObject.Properties | Select-Object -First 1).Value }
  return $dl.url
}

function Test-Jar($path) {
  if (-not (Test-Path $path)) { return $false }
  if ((Get-Item $path).Length -lt 1000) { return $false }
  $fs = [System.IO.File]::OpenRead($path)
  $b0 = $fs.ReadByte(); $b1 = $fs.ReadByte(); $fs.Close()
  return ($b0 -eq 0x50 -and $b1 -eq 0x4B)   # naglowek ZIP "PK"
}

Write-Host "==> Paper ${PaperVer}: pobieranie listy buildow (Fill API v3)..."
$pb = Invoke-RestMethod -Headers $hdr "$API/projects/paper/versions/$PaperVer/builds"
$purl = Get-JarUrl $pb
if (-not $purl) { Write-Error "Nie znaleziono STABLE builda Paper dla $PaperVer."; exit 1 }
Write-Host "    URL: $purl"
Invoke-WebRequest -Headers $hdr $purl -OutFile "$env:TEMP\paper.jar"
if (-not (Test-Jar "$env:TEMP\paper.jar")) { Write-Error "Pobranie Paper nieudane (uszkodzony plik)."; exit 1 }

# Rozdanie do KAZDEGO folderu w servers\ (auto-wykrywanie - obejmuje nowe tryby).
$count = 0
Get-ChildItem -Directory servers | ForEach-Object {
  Copy-Item "$env:TEMP\paper.jar" (Join-Path $_.FullName "paper.jar") -Force
  Write-Host "    -> servers\$($_.Name)\paper.jar"
  $count++
}
if ($count -eq 0) { Write-Error "Brak folderow w servers\ - nie ma gdzie skopiowac paper.jar."; exit 1 }
Write-Host "    Paper rozdany do $count serwerow."

Write-Host "==> Velocity: ustalanie najnowszej wersji (Fill API v3)..."
$vp = Invoke-RestMethod -Headers $hdr "$API/projects/velocity"
$allv = @()
foreach ($p in $vp.versions.PSObject.Properties) { $allv += $p.Value }
$vv = $allv | Sort-Object { try { [version]([regex]::Match($_, '\d+(\.\d+)+').Value) } catch { [version]"0.0" } } | Select-Object -Last 1
if (-not $vv) { Write-Error "Nie udalo sie ustalic wersji Velocity."; exit 1 }
$vb = Invoke-RestMethod -Headers $hdr "$API/projects/velocity/versions/$vv/builds"
$vurl = Get-JarUrl $vb
if (-not $vurl) { Write-Error "Brak builda Velocity dla $vv."; exit 1 }
Write-Host "    Velocity ${vv} URL: $vurl"
Invoke-WebRequest -Headers $hdr $vurl -OutFile "velocity\velocity.jar"
if (-not (Test-Jar "velocity\velocity.jar")) { Write-Error "Pobranie Velocity nieudane."; exit 1 }

Write-Host "==> Gotowe. Kolejnosc startu: limbo -> lobby -> survival -> velocity."
