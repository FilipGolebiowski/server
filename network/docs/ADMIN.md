# El Cartel — obsługa serwera (Linux)

Komendy do codziennej obsługi sieci na Linuksie. Każdy serwer (limbo, shardy, velocity) to osobny proces Javy. Start i struktura: **[START.md](START.md)**. Hardening OS / firewall / TCPShield: `../BLUEPRINT.md` sek. 3–4.

## Deploy na OVH (test — budżet ≤ 50 zł/mies.)

Cały minimalny stack (proxy + limbo + hub + kilka sektorów survival + bazy) jedzie na **jednym VPS**.

**Co kupić — OVH VPS-2:** 4 vCore / **8 GB RAM** / 75 GB NVMe, ~**35–40 zł/mies.** (jedyny płatny element). W cenie: anti-DDoS, backup dobowy, nielimitowany transfer. To najwięcej RAM w tym budżecie, a sektory to kilka procesów survival naraz.

- **System:** Ubuntu 24.04 LTS. **Region:** Warszawa (najniższy ping w PL) albo Frankfurt.
- **Bazy poza VPS (za darmo, oszczędza RAM):** Mongo → **MongoDB Atlas M0** (free 512 MB), Redis → **Redis Cloud** (free — masz już URI). Wtedy VPS robi tylko JVM-y.
- **Taniej przy sporadycznych testach:** OVH **Public Cloud** godzinowo (instancja 8–16 GB ≈ €0,03–0,06/h) — odpalasz na kilka godzin, kasujesz, płacisz grosze.

**Budżet RAM na 8 GB (bazy zewnętrzne) — Xmx do testu:**

| Proces | Xmx |
|---|---|
| velocity | 0,75 G |
| limbo | 0,5 G |
| hub-1 | 1 G |
| survival ×3 (sektory 0,0 · 1,0 · 0,1) | 3× 1,25 G |
| system + zapas | ~1,2 G |

Razem ~6,8 G / 8 G. `view-distance=6`, `simulation-distance=4` (mniej wczytywanych chunków).

**Kroki:**

1. Zamów VPS-2 (Ubuntu 24.04, klucz SSH), zanotuj IP.
2. `ssh ubuntu@IP` → `sudo apt update && sudo apt upgrade -y`.
3. **Firewall:** `sudo ufw allow 22 && sudo ufw allow 25565 && sudo ufw enable`. Mongo/Redis **nie** wystawiaj (zostają na localhost albo są zewnętrzne).
4. Zależności — patrz sekcja **Instalacja zależności** niżej (Java 21, tmux; Mongo/Redis tylko jeśli trzymasz je lokalnie zamiast Atlas/Redis Cloud).
5. Wgraj projekt: `git clone <repo>` albo `scp -r elcartelgg ubuntu@IP:~/`.
6. Sekrety: uzupełnij `network/config/elcartel.properties` (Mongo = URI Atlas, Redis = URI Redis Cloud), `chmod 600`.
7. Build + jary + core: `cd mc-core && gradle build` → `cd ../network && bash setup.sh && bash scripts/deploy-core.sh`.
8. Sektory: `bash scripts/new-shard.sh hub 1 1G`, potem `bash scripts/new-shard.sh survival 1 1250M --sector 0 0` (oraz `--sector 1 0`, `--sector 0 1`). Wszystkie survival mają ten sam `level-seed` (już w `templates/survival`).
9. Start: `bash start-all.sh` (dev) albo tmux/systemd (sekcje niżej).
10. **DNS:** rekord A subdomeny (np. `test.elcartel.gg`) → IP VPS-a; łączysz się `test.elcartel.gg:25565` (albo graj na samym `IP:25565`).

Do testu wystarczy UFW + logowanie kluczem SSH. Pełny hardening (Cloudflare/TCPShield, nftables) jest w `BLUEPRINT.md` sek. 3–4.

## Instalacja zależności (Debian/Ubuntu)

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk tmux            # Java 21 + tmux
# MongoDB (najprościej w Dockerze):
docker run -d --name mongo -p 27017:27017 -v mongo:/data/db mongo:7
# Redis: użyj zewnętrznego (URI w elcartel.properties) albo lokalnie:
sudo apt install -y redis-server
java -version   # potwierdź 21
```

Zabezpiecz sekrety: `chmod 600 elcartel.properties velocity/forwarding.secret`.

## Trwałe uruchomienie

### A) tmux — proste, z dostępem do konsoli (każdy serwer = sesja)

```bash
cd network
tmux new -d -s limbo      'bash servers/limbo/start.sh'
tmux new -d -s hub-1      'bash servers/hub-1/start.sh'
tmux new -d -s survival-1 'bash servers/survival-1/start.sh'
tmux new -d -s velocity   'bash velocity/start.sh'
```

- Lista sesji: `tmux ls`
- Wejście do konsoli serwera: `tmux attach -t survival-1` → wyjście **bez** zabijania: `Ctrl+b`, potem `d`
- Komenda do konsoli bez wchodzenia: `tmux send-keys -t survival-1 'say Restart za 5 min' Enter`

### B) systemd — produkcja (autostart + auto-restart)

`/etc/systemd/system/elcartel@.service` (jeden szablon dla wszystkich backendów):

```ini
[Unit]
Description=El Cartel MC %i
After=network-online.target

[Service]
Type=simple
User=mc
WorkingDirectory=/opt/elcartel/network/servers/%i
ExecStart=/usr/bin/env bash start.sh
Restart=on-failure
RestartSec=5
SuccessExitStatus=0 143

[Install]
WantedBy=multi-user.target
```

- Włącz + start: `sudo systemctl enable --now elcartel@limbo elcartel@hub-1 elcartel@survival-1`
- Proxy: osobny unit z `WorkingDirectory=.../network/velocity`.
- ENV per-serwer (ROLE/SHARD_ID/MODE) ustawia `start.sh`; sekrety czyta core z `../../elcartel.properties`.
- Konsola serwera pod systemd wymaga FIFO na stdin (lub po prostu używaj tmux). Graceful stop: patrz niżej.

## Status / co działa

```bash
ps aux | grep -E 'paper.jar|velocity.jar' | grep -v grep   # procesy
ss -ltnp | grep -E ':(25565|255[0-9][0-9])'                # porty (proxy 25565, limbo 25567, hub 25500+, survival 25600+)
jps -l                                                     # procesy Javy
systemctl status 'elcartel@*'                              # gdy systemd
```

## Stop / restart (GRACEFUL — zapisuje świat!)

```bash
# najlepiej: wpisz 'stop' w konsoli serwera
tmux send-keys -t survival-1 stop Enter          # backend Paper
tmux send-keys -t velocity   end Enter           # proxy Velocity (lub 'shutdown')
# systemd:
sudo systemctl restart elcartel@survival-1
# awaryjnie (mniej grzecznie, bez zapisu):
pkill -f survival-1
pkill -f 'paper.jar|velocity.jar'                # wszystko
```

## Logi

```bash
tail -f network/.logs/survival-1.log        # gdy start-all.sh (nohup)
tail -f servers/survival-1/logs/latest.log  # log Papera danego sharda
tail -f velocity/logs/latest.log            # log proxy
journalctl -u elcartel@survival-1 -f        # gdy systemd
```

## Backup

```bash
# Baza (profile, ekonomia, auth, rezerwacje nicków):
mongodump --uri "mongodb://localhost:27017/elcartel" --out backups/$(date +%F)
mongorestore --uri "mongodb://localhost:27017/elcartel" backups/2026-06-22   # odtworzenie

# Światy (najlepiej po 'save-all' w konsoli, albo gdy serwer stoi):
tmux send-keys -t survival-1 save-all Enter
tar czf backups/survival-1-world-$(date +%F).tgz -C servers/survival-1 world

# Sekrety offline: elcartel.properties, velocity/forwarding.secret (NIGDY do repo).
```

Cron (codzienny dump baz o 4:00): `0 4 * * * cd /opt/elcartel/network && mongodump --uri "mongodb://localhost:27017/elcartel" --out backups/$(date +\%F)`

## Monitoring zasobów

```bash
htop                 # CPU/RAM per proces (Paper jest single-core-bound)
free -h              # RAM
df -h                # dysk (światy rosną!)
du -sh servers/*     # rozmiar shardów
```

W grze: `/spark tps` i `/spark health` (plugin spark jest w jarach) — TPS, MSPT, GC.

## Aktualizacja core po zmianie kodu

```bash
cd mc-core && gradle build
cd ../network && bash deploy-core.sh
# restart serwerow (graceful przez konsole/systemd)
```

Nowe shardy biorą świeży jar automatycznie (`deploy-core` kładzie też do `templates/`).

## Skalowanie

```bash
bash new-shard.sh survival 2     # dolóż 2 shardy survival
# wystartuj nowe (tmux/systemd) -> proxy wykryje je z Redisa (~3 s), bez restartu proxy
```

Zdjęcie sharda: `stop` w jego konsoli → po opróżnieniu proxy przestaje na niego routować (heartbeat wygasa po ~5 s). Folder usuń, jeśli na stałe.

## Moderacja (kary)

Komendy działają na **proxy** (Velocity), więc z dowolnego trybu i z konsoli proxy. **Bez sufiksu = kara na tryb** (tryb nadawcy lub `-t <tryb>`); **sufiks `proxy` = cała sieć**. Czas: `30m`/`2h`/`7d`/`1w`/`perm` (brak = na stałe). Ostatni token `pokaz`/`cichy` steruje ogłoszeniem na czacie (domyślnie `pokaz`). Szczegóły: `../BLUEPRINT-kary.md`.

```
/<komenda>      <gracz> [czas] [powód...] [pokaz|cichy]   # tryb nadawcy (lub -t <tryb>)
/<komenda>proxy <gracz> [czas] [powód...] [pokaz|cichy]   # cała sieć
# komendy: ban unban mute unmute kick warn  (+ wariant ...proxy każdej)
```

```bash
# przyklady
/ban Gracz 7d grief             # ban na trybie nadawcy, 7 dni, ogloszony
/banproxy Gracz cheaty          # ban na cala siec, na stale
/ban Gracz 1d spam cichy        # ban na tryb, bez ogloszenia na czacie
/ban Gracz -t oceanblock 3d xray# ban na oceanblock z dowolnego miejsca
/mute Gracz 1h spam             # mute na trybie nadawcy
/unbanproxy Gracz               # zdjecie bana sieciowego
```

- **Ban sieciowy** (`...proxy`) odrzuca przy logowaniu; **ban trybu** blokuje wejście na shardy tego trybu (gracz zostaje na hubie/innym trybie).
- **Mute** egzekwują shardy na czacie; zmiany na żywo idą kanałem `core:punish` (gracz online dostaje skutek od razu).
- **Teksty kar** (ekran bana, ogłoszenia, mute itd.) konfigurujesz w `messages.properties` (kody `&`, placeholdery `{player}`/`{reason}`/`{duration}`/`{scope}`/`{by}`). Po edycji zrestartuj serwery.
- Rekordy w kolekcji `punishments` (`mongosh`: `db.punishments.find({uuid: UUID("...")})`). Wygasanie leniwe — wygasłe kary zostają jako historia.
- Cel po nicku — gracz musi być online albo wcześniej wejść (jest w `players`).

## Uprawnienia (LuckPerms)

Uprawnienia i rangi obsługuje **LuckPerms** (nie własny system). Potrzebny na **Velocity** (komendy kar) i na **Paper** (shardy + limbo).

```bash
# JAR-y (deploy-core tego NIE rusza — instalujesz ręcznie):
#   servers/*/plugins/LuckPerms-Bukkit.jar
#   velocity/plugins/LuckPerms-Velocity.jar
```

### Wspólna baza (Mongo + Redis)

Jeden rejestr uprawnień dla całej sieci — `/ban` na proxy widzi te same permy co nadajesz na hubie.

```bash
cd network
python configure-luckperms.py          # czyta elcartel.properties, patchuje wszystkie config.yml
# Windows: python configure-luckperms.py  /  powershell -File configure-luckperms.ps1
```

Skrypt ustawia na **velocity + każdy shard**:
- `storage-method: mongodb` + URI z `ELCARTEL_MONGO_URI` / `ELCARTEL_MONGO_DB`
- `messaging-service: redis` + dane z `ELCARTEL_REDIS_URI` (propagacja zmian między serwerami)

Po uruchomieniu skryptu **zrestartuj velocity i wszystkie shardy**.

**Pierwsza konfiguracja rang** (konsola velocity lub dowolny serwer — wystarczy raz):

```
lp creategroup admin
lp group admin permission set elcartel.ban true
lp group admin permission set elcartel.mute true
lp group admin permission set elcartel.kick true
lp group admin permission set elcartel.warn true
lp group admin permission set elcartel.eco.admin true
lp user Croofix parent add admin
lp networksync
```

Stare uprawnienia z lokalnego H2 **nie migrują** — nadaj je ponownie po przejściu na Mongo.

**Nowy shard:** zainstaluj LuckPerms-Bukkit.jar, uruchom serwer raz (generuje config), potem ponownie `configure-luckperms.py`.

Węzły kar: `elcartel.ban`, `elcartel.mute`, `elcartel.kick`, `elcartel.warn`; ekonomia-admin: `elcartel.eco.admin`. Rdzeń tylko **czyta** uprawnienia (`hasPermission`).

## Panel web (przeglądarka)

Lekki panel administracyjny — status całej sieci bez wchodzenia w tmux.

```bash
cd network
bash start-panel.sh              # Linux
# Windows: powershell -ExecutionPolicy Bypass -File start-panel.ps1
# Otwórz: http://127.0.0.1:8080
```

**Wymaga:** Python 3.10+, `elcartel.properties` z URI Mongo/Redis (jak backendy).

**Co pokazuje i robi:**
- **Dashboard** — proxy, Redis/Mongo, gracze, TPS, start/stop całej sieci.
- **Shardy** — kliknij wiersz → **szczegóły** (port, ENV, Redis hash, logi, sesje); start/stop; usuń katalog lub wpis Redis.
- **Serwery** — limbo, velocity, shardy z portami i akcjami.
- **Sesje** — gracze online; usuń sesję; skok do sharda.
- **Redis** — rejestr shardów, martwe wpisy, przeglądarka kluczy (`shard:*`, `session:*`, `lock:profile:*`).
- **Dev** — preflight (Java, porty, jary), Mongo stats, `deploy-core`, `configure-luckperms`.
- **Logi** — tail `latest.log`.

**Jak działa start/stop:**

| Platforma | Start (domyślnie) | Stop (graceful) |
|---|---|---|
| **Linux + tmux** | `tmux new -d -s <id> 'bash start.sh'` | `tmux send-keys -t <id> stop Enter` (velocity: `end`) |
| **Windows / bez tmux** | Proces w tle, log w `.logs/` | `stop` / `end` przez stdin (działa, dopóki panel działa) |
| **Windows + „Okno konsoli”** | Osobne okno `start.bat` (jak `start-all.ps1`) | Wpisz `stop` ręcznie w oknie |

Przy nieudanym graceful stop panel zaproponuje **awaryjne** zatrzymanie procesu po porcie (bez gwarancji `save-all`).

Auto-odśwież co 5 s (zgodnie ze `STALE_MS=5000` w core).

**Bezpieczeństwo (produkcja):**
- Domyślnie bind na `127.0.0.1` — nie wystawiaj publicznie bez reverse proxy + auth.
- Ustaw `ELCARTEL_PANEL_TOKEN` w `elcartel.properties` — wymagany przy starcie/stopie, tworzeniu i usuwaniu shardów.
- Panel czyta te same sekrety co core; trzymaj go w sieci prywatnej (jak Mongo/Redis w BLUEPRINT.md).
