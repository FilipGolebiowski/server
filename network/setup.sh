#!/usr/bin/env bash
# Pobiera jary przez PaperMC Fill API (v3 - fill.papermc.io):
#   - Paper 1.21.11 (najnowszy STABLE build) -> do KAZDEGO folderu w servers/
#     (lobby, limbo, survival oraz dowolne dodane tryby)
#   - najnowszy Velocity -> velocity/velocity.jar
#
# Uwaga: Fill API wymaga NIEGENERYCZNEGO User-Agent (curl/wget sa odrzucane).
# Stare API v2 (api.papermc.io/v2) zostalo wylaczone - dlatego ten skrypt
# uzywa v3. Wymaga: bash, curl, python3.
set -euo pipefail
cd "$(dirname "$0")"

PAPER_VER="1.21.11"
# Mozesz podmienic kontakt ponizej na wlasny (URL/email) - byle nie generyczny.
UA="elcartelgg-network-setup/1.0 (+https://elcartel.gg)"
API="https://fill.papermc.io/v3"

# Parser: czyta liste buildow (JSON) ze stdin -> wypisuje URL pobrania.
# Preferuje kanal STABLE i najwyzszy numer builda; klucz pliku: server:default
# (z fallbackiem na pierwszy dostepny download).
pick_url() {
  python3 - <<'PY'
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(""); sys.exit(0)
builds = d if isinstance(d, list) else d.get("builds", [])
stable = [b for b in builds if str(b.get("channel", "")).upper() == "STABLE"]
pool = stable or builds
if not pool:
    print(""); sys.exit(0)
b = max(pool, key=lambda x: x.get("id", x.get("build", 0)))
dls = b.get("downloads", {}) or {}
entry = dls.get("server:default") or (next(iter(dls.values())) if dls else None)
print((entry or {}).get("url", ""))
PY
}

# Weryfikacja: plik istnieje, nie jest pusty i ma naglowek ZIP ("PK") - czyli to jar, nie strona bledu.
verify_jar() {
  [ -s "$1" ] || { echo "    ! plik pusty/nie istnieje: $1"; return 1; }
  if [ "$(head -c2 "$1")" != "PK" ]; then
    echo "    ! to nie jest poprawny .jar (HTML/blad zamiast pliku?): $1"; return 1
  fi
  return 0
}

echo "==> Paper ${PAPER_VER}: pobieranie listy buildow (Fill API v3)..."
PURL=$(curl -fsSL -H "User-Agent: ${UA}" "${API}/projects/paper/versions/${PAPER_VER}/builds" | pick_url || true)
if [ -z "${PURL}" ]; then
  echo "BLAD: nie znaleziono STABLE builda Paper dla ${PAPER_VER}."
  echo "      Sprawdz, czy wersja jest poprawna i czy masz dostep do fill.papermc.io."
  exit 1
fi
echo "    URL: ${PURL}"
curl -fSL -H "User-Agent: ${UA}" -o /tmp/paper.jar "${PURL}"
verify_jar /tmp/paper.jar || { echo "BLAD: pobranie Paper nieudane."; exit 1; }

# Rozdanie do KAZDEGO folderu w servers/ (auto-wykrywanie - obejmuje nowe tryby).
COUNT=0
for D in servers/*/; do
  [ -d "$D" ] || continue
  cp /tmp/paper.jar "${D}paper.jar"
  echo "    -> ${D}paper.jar"
  COUNT=$((COUNT + 1))
done
if [ "${COUNT}" -eq 0 ]; then
  echo "BLAD: brak folderow w servers/ - nie ma gdzie skopiowac paper.jar."; exit 1
fi
echo "    Paper rozdany do ${COUNT} serwerow."

echo "==> Velocity: ustalanie najnowszej wersji (Fill API v3)..."
VVER=$(curl -fsSL -H "User-Agent: ${UA}" "${API}/projects/velocity" | python3 -c "
import sys, json, re
try:
    d = json.load(sys.stdin)
except Exception:
    print(''); sys.exit(0)
v = d.get('versions', {})
allv = []
if isinstance(v, dict):
    for arr in v.values():
        allv += arr
else:
    allv = list(v)
allv = [x for x in allv if x]
def vkey(s):
    return [(1, int(p)) if p.isdigit() else (0, p) for p in re.split(r'[.\-+_]', str(s))]
print(sorted(allv, key=vkey)[-1] if allv else '')
" || true)
if [ -z "${VVER}" ]; then echo "BLAD: nie udalo sie ustalic wersji Velocity."; exit 1; fi
VURL=$(curl -fsSL -H "User-Agent: ${UA}" "${API}/projects/velocity/versions/${VVER}/builds" | pick_url || true)
if [ -z "${VURL}" ]; then echo "BLAD: brak builda Velocity dla ${VVER}."; exit 1; fi
echo "    Velocity ${VVER} URL: ${VURL}"
curl -fSL -H "User-Agent: ${UA}" -o velocity/velocity.jar "${VURL}"
verify_jar velocity/velocity.jar || { echo "BLAD: pobranie Velocity nieudane."; exit 1; }

chmod +x velocity/start.sh servers/*/start.sh 2>/dev/null || true
echo "==> Gotowe. Kolejnosc startu: limbo -> lobby -> survival -> velocity."
