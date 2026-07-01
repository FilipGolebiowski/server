#!/usr/bin/env bash
# El Cartel - preflight do testu lokalnego sieci (Linux/macOS).
# Sprawdza: Java 21+, RAM, wolne porty 25565-25568, obecnosc jarow,
# zgodnosc forwarding.secret z paper-global.yml kazdego backendu, eula=true.
# Nic nie uruchamia ani nie zmienia - tylko diagnostyka.
cd "$(dirname "$0")/.."
PASS=0; WARN=0; FAIL=0
ok()   { echo "  [OK]   $1"; PASS=$((PASS+1)); }
warn() { echo "  [WARN] $1"; WARN=$((WARN+1)); }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }

echo "== El Cartel preflight =="

# --- Java 21+ ---
if command -v java >/dev/null 2>&1; then
  RAW=$(java -version 2>&1 | head -1 | grep -oE '"[0-9._]+"' | tr -d '"')
  MAJ=${RAW%%.*}
  if [ "$MAJ" = "1" ]; then MAJ=$(echo "$RAW" | cut -d. -f2); fi
  if [ "${MAJ:-0}" -ge 21 ] 2>/dev/null; then ok "Java $MAJ (>=21)"
  else fail "Java $MAJ - wymagane 21+ (Paper 1.21.8 / Velocity). Zainstaluj JDK 21 (Temurin)"; fi
else
  fail "brak Javy w PATH - zainstaluj JDK 21 (Temurin)"
fi

# --- RAM ---
TOTMB=""
if command -v free >/dev/null 2>&1; then TOTMB=$(free -m | awk '/^Mem:/{print $2}')
elif sysctl -n hw.memsize >/dev/null 2>&1; then TOTMB=$(( $(sysctl -n hw.memsize)/1024/1024 )); fi
if [ -n "$TOTMB" ]; then
  if [ "$TOTMB" -ge 8000 ]; then ok "RAM ${TOTMB} MB"
  else warn "RAM ${TOTMB} MB - heapy w start.* suma ~9 GB; zmniejsz -Xmx albo testuj mniej serwerow naraz"; fi
else warn "nie rozpoznano ilosci RAM"; fi

# --- Porty wolne ---
for P in 25565 25566 25567 25568; do
  BUSY=""
  if command -v ss >/dev/null 2>&1; then ss -ltn 2>/dev/null | grep -q ":$P " && BUSY=1
  elif command -v lsof >/dev/null 2>&1; then lsof -iTCP:$P -sTCP:LISTEN >/dev/null 2>&1 && BUSY=1; fi
  if [ -n "$BUSY" ]; then fail "port $P zajety (cos juz nasluchuje)"; else ok "port $P wolny"; fi
done

# --- Jary ---
[ -f velocity/velocity.jar ] && ok "velocity/velocity.jar" || fail "brak velocity/velocity.jar -> uruchom: bash setup.sh"
for S in lobby limbo survival; do
  [ -f "servers/$S/paper.jar" ] && ok "servers/$S/paper.jar" || fail "brak servers/$S/paper.jar -> uruchom: bash setup.sh"
done

# --- Zgodnosc sekretu ---
if [ -f velocity/forwarding.secret ]; then
  SEC=$(tr -d ' \t\r\n' < velocity/forwarding.secret)
  for S in lobby limbo survival; do
    F="servers/$S/config/paper-global.yml"
    if [ -f "$F" ]; then
      PS=$(grep -E "^[[:space:]]*secret:" "$F" | sed -E "s/.*secret:[[:space:]]*['\"]?([^'\"]+)['\"]?.*/\1/" | tr -d ' \t\r')
      if [ "$PS" = "$SEC" ]; then ok "$S secret zgodny z proxy"
      else fail "$S secret NIEzgodny (paper='$PS' vs velocity='$SEC')"; fi
    else fail "brak $F"; fi
  done
else fail "brak velocity/forwarding.secret"; fi

# --- EULA ---
for S in lobby limbo survival; do
  grep -q '^eula=true' "servers/$S/eula.txt" 2>/dev/null && ok "$S eula=true" || fail "$S eula != true (servers/$S/eula.txt)"
done

echo "== Wynik: OK=$PASS  WARN=$WARN  FAIL=$FAIL =="
if [ "$FAIL" -eq 0 ]; then
  echo "Gotowe do startu. Kolejnosc: limbo -> lobby -> survival -> velocity. Szczegoly: TEST.md"
else
  echo "Napraw pozycje [FAIL] przed startem (patrz TEST.md)."
fi
exit "$FAIL"
