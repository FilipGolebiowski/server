# El Cartel - preflight do testu lokalnego sieci (Windows / PowerShell).
# Sprawdza: Java 21+, RAM, wolne porty 25565-25568, obecnosc jarow,
# zgodnosc forwarding.secret z paper-global.yml kazdego backendu, eula=true.
# Nic nie uruchamia ani nie zmienia - tylko diagnostyka.
# Uruchom:  powershell -ExecutionPolicy Bypass -File preflight.ps1
$ErrorActionPreference = 'SilentlyContinue'
Set-Location (Split-Path $PSScriptRoot -Parent)
$script:pass = 0; $script:warn = 0; $script:fail = 0
function Ok($m)   { Write-Host "  [OK]   $m" -ForegroundColor Green;  $script:pass++ }
function Warn($m) { Write-Host "  [WARN] $m" -ForegroundColor Yellow; $script:warn++ }
function Fail($m) { Write-Host "  [FAIL] $m" -ForegroundColor Red;    $script:fail++ }

Write-Host "== El Cartel preflight =="

# --- Java 21+ ---
$jv = (& java -version 2>&1)
if ($jv) {
  $line = ($jv | Select-Object -First 1).ToString()
  if ($line -match '"([0-9]+)(\.([0-9]+))?') {
    $maj = [int]$Matches[1]; if ($maj -eq 1) { $maj = [int]$Matches[3] }
    if ($maj -ge 21) { Ok "Java $maj (>=21)" }
    else { Fail "Java $maj - wymagane 21+ (Paper 1.21.8 / Velocity). Zainstaluj JDK 21 (Temurin)" }
  } else { Warn "nie rozpoznano wersji Javy z: $line" }
} else { Fail "brak Javy w PATH - zainstaluj JDK 21 (Temurin)" }

# --- RAM ---
$ram = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
if ($ram -ge 8) { Ok "RAM $ram GB" }
else { Warn "RAM $ram GB - heapy w start.bat suma ~9 GB; zmniejsz -Xmx albo testuj mniej serwerow naraz" }

# --- Porty wolne ---
foreach ($p in 25565, 25566, 25567, 25568) {
  $busy = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
  if ($busy) { Fail "port $p zajety (cos juz nasluchuje)" } else { Ok "port $p wolny" }
}

# --- Jary ---
if (Test-Path 'velocity\velocity.jar') { Ok 'velocity\velocity.jar' }
else { Fail 'brak velocity\velocity.jar -> uruchom: powershell -ExecutionPolicy Bypass -File setup.ps1' }
foreach ($s in 'lobby', 'limbo', 'survival') {
  if (Test-Path "servers\$s\paper.jar") { Ok "servers\$s\paper.jar" }
  else { Fail "brak servers\$s\paper.jar -> uruchom setup.ps1" }
}

# --- Zgodnosc sekretu ---
if (Test-Path 'velocity\forwarding.secret') {
  $sec = (Get-Content 'velocity\forwarding.secret' -Raw).Trim()
  foreach ($s in 'lobby', 'limbo', 'survival') {
    $f = "servers\$s\config\paper-global.yml"
    if (Test-Path $f) {
      $c = Get-Content $f -Raw
      if ($c -match "secret:\s*['""]?([^'""\r\n]+)") {
        $ps = $Matches[1].Trim()
        if ($ps -eq $sec) { Ok "$s secret zgodny z proxy" }
        else { Fail "$s secret NIEzgodny (paper='$ps' vs velocity='$sec')" }
      } else { Fail "$s - nie znaleziono klucza 'secret' w paper-global.yml" }
    } else { Fail "brak $f" }
  }
} else { Fail 'brak velocity\forwarding.secret' }

# --- EULA ---
foreach ($s in 'lobby', 'limbo', 'survival') {
  if ((Get-Content "servers\$s\eula.txt" -Raw) -match 'eula=true') { Ok "$s eula=true" }
  else { Fail "$s eula != true (servers\$s\eula.txt)" }
}

Write-Host "== Wynik: OK=$script:pass  WARN=$script:warn  FAIL=$script:fail =="
if ($script:fail -eq 0) {
  Write-Host 'Gotowe do startu. Kolejnosc: limbo -> lobby -> survival -> velocity. Szczegoly: TEST.md' -ForegroundColor Green
} else {
  Write-Host 'Napraw pozycje [FAIL] przed startem (patrz TEST.md).' -ForegroundColor Red
}
exit $script:fail
