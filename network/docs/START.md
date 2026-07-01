# Jak wystartować sieć (shardy + tryby)

Struktura: **limbo** (gateway auth) + **shardy** (hub, survival, oceanblock…, tworzone przez `new-shard`) + **velocity** (proxy). Hub (lobby) jest trybem shardowanym. **Codzienna obsługa na Linuksie** (trwały start, logi, backup, restart, monitoring): **[ADMIN.md](ADMIN.md)**.

## Jak uruchamiać skrypty — ZACZNIJ TU

- **Linux (serwer produkcyjny):** skrypty `.sh`. Raz nadaj prawa: `chmod +x *.sh servers/*/start.sh velocity/start.sh`, potem `bash <skrypt>` albo `./<skrypt>`.
- **Windows:** skrypty `.ps1`. `powershell -ExecutionPolicy Bypass -File <skrypt>` (działa też z cmd) albo `.\<skrypt>` (jeśli blokuje: `Set-ExecutionPolicy -Scope Process Bypass`).

Poniżej podaję obie wersje.

## 0. Wymagania

- **MongoDB** na `localhost:27017` · **Redis** (URI w `elcartel.properties`) · **JDK 21**.
- Sekrety (Mongo/Redis) w `network/elcartel.properties`. Pełne ENV: `../mc-core/README.md`. Instalacja zależności na Linuksie: **[ADMIN.md](ADMIN.md)**.

## 1. Zbuduj i wgraj core

```bash
# w mc-core/
gradle build                 # lub ./gradlew build (Linux) / gradlew.bat build (Windows)
# w network/
bash deploy-core.sh                                        # Linux
powershell -ExecutionPolicy Bypass -File deploy-core.ps1   # Windows
```

`deploy-core` wgrywa `core-velocity` → velocity, `core-paper` → limbo + wszystkie shardy, `mode-<tryb>` → shardy danego trybu.

## 2. Utwórz shardy (hub + tryby)

### Hub i tryby bez sektorów

```bash
# Linux                                # Windows
bash new-shard.sh hub 1                # new-shard.ps1 -Mode hub -Count 1
bash new-shard.sh oceanblock 1         # new-shard.ps1 -Mode oceanblock -Count 1
```

Potrzebny **min. jeden shard hub** (tam ląduje gracz po logowaniu).

### Survival — sektory (inaczej niż zwykły shard)

Survival dzieli **jedną wspólną mapę** na kwadraty (sektory, domyślnie 1000×1000 bloków). Każdy sektor to osobny shard z własnymi współrzędnymi `(sx, sz)` w rejestrze. Przejście przez granicę sektora przerzuca gracza na sąsiedni podserwer — współrzędne X/Z pozostają ciągłe dzięki **wspólnemu seedowi** (`templates/survival/server.properties.extra` → `level-seed`; ta sama wartość na każdym shardzie survival).

**Sektor spawnowy** `(0, 0)` — tu lądują nowi gracze po `/play survival`. Kolejne sektory to fragmenty mapy wokół spawnu; bez sektora `(0, 0)` tryb nie ma punktu startowego.

```bash
# Linux                                                    # Windows
bash new-shard.sh survival 1 2G --sector 0 0               # new-shard.ps1 -Mode survival -SectorX 0 -SectorZ 0
bash new-shard.sh survival 1 2G --sector 1 0               # new-shard.ps1 -Mode survival -SectorX 1 -SectorZ 0
bash new-shard.sh survival 1 2G --sector 0 1               # new-shard.ps1 -Mode survival -SectorX 0 -SectorZ 1
bash new-shard.sh survival 1 2G --sector -1 0              # new-shard.ps1 -Mode survival -SectorX -1 -SectorZ 0
# replika zatłoczonego sektora (ten sam sx,sz → kolejny survival-N):
bash new-shard.sh survival 1 2G --sector 0 0               # new-shard.ps1 -Mode survival -SectorX 0 -SectorZ 0
# opcjonalnie inny rozmiar sektora (spójny wszędzie):
bash new-shard.sh survival 1 2G --sector 0 0 --size 1000   # ... -SectorSize 1000
```

Skrypt zapisuje do `start.sh` / `start.bat` zmienne `ELCARTEL_SECTOR_X`, `ELCARTEL_SECTOR_Z`, `ELCARTEL_SECTOR_SIZE`, `ELCARTEL_WORLD`. Nazwy shardów zostają `survival-N` (identyfikacja sektora jest w Redis, nie w nazwie folderu).

Minimalny dev: **co najmniej sektor `(0, 0)`**. Większa mapa = więcej sąsiadów w siatce (np. `(1,0)`, `(0,1)`, `(-1,0)`…). Szczegóły mechaniki: **[../BLUEPRINT-sektory.md](../BLUEPRINT-sektory.md)**.

Po utworzeniu shardów odpal ponownie `deploy-core`, by mieć w nich świeże jary core.

## 3. Start

```bash
# Linux (w tle, logi w .logs/) — do dev / szybkiego startu
bash start-all.sh
# Windows (osobne okna)
powershell -ExecutionPolicy Bypass -File start-all.ps1
```

Kolejność: **limbo → shardy → velocity**. **Produkcja na Linuksie** — uruchamiaj przez **tmux** lub **systemd** (graceful stop, auto-restart, dostęp do konsoli): patrz **[ADMIN.md](ADMIN.md)**.

Ręcznie: `bash servers/limbo/start.sh`, potem `start.sh` każdego sharda, na końcu `bash velocity/start.sh`.

## 4. Przepływ gracza

1. Łączysz się na proxy (`<adres>:25565`) → trafiasz na **limbo** (auth).
2. Premium → auto-login; cracked → `/register` / `/login`.
3. Po autoryzacji proxy przerzuca Cię na najmniej obciążony **hub-shard**.
4. `/play survival` → najmniej obciążony **sektor spawnowy** `(0, 0)` (nie losowy shard survival).
5. Na survivalu: idąc przez **worldborder** na skraju sektora przechodzisz na sąsiedni sektor (transfer bez zmiany współrzędnych X/Z).
6. **`/ch`** (alias `/sektory`) — GUI z **sektorami spawnowymi** (repliki `(0,0)`); widać obłożenie, kliknięcie przełącza kanał.
7. **`/spawn`** — teleport na punkt spawnu z `config.yml` (na spawnie) albo przerzut z dowolnego sektora z powrotem na spawn.
8. **`/channels survival`** (proxy) — lista kanałów trybu; na survivalu pokazuje tylko sektory spawnowe (jak `/ch`).

Dane gracza są **per-tryb** (osobna ekonomia/ekwipunek survival vs oceanblock), konto (nick, premium, ranga) wspólne; profil podąża między shardami tego samego trybu (w tym między sektorami i replikami).

## 5. Panel web (opcjonalnie)

Po starcie sieci możesz odpalić panel administracyjny:

```bash
bash start-panel.sh    # http://127.0.0.1:8080
```

Monitoring shardów, sesji, logów, **uruchamianie i zatrzymywanie serwerów** z przeglądarki — patrz **[ADMIN.md](ADMIN.md)** sekcja „Panel web”.

## Struktura katalogów

```
network/
  servers/limbo/   hub-1/  survival-1/ …   # limbo (staly) + shardy
  templates/_base  hub/ survival/ oceanblock/
  velocity/                                # proxy
  new-shard.*  deploy-core.*  start-all.*  preflight.*  setup.*
```

## Dodanie nowego trybu

`mkdir templates/<tryb>` + `mode.conf` (port-base, capy) + `server.properties.extra` (świat); opcjonalnie własny plugin trybu (moduł `mode-<tryb>` w `mc-core`). Potem `new-shard <tryb> <N>`. Zero zmian w rdzeniu. Szczegóły: `templates/README.md`, `../BLUEPRINT-modes.md`.

**Sektory** (podział mapy na shardy z ciągłym terenem) — na razie tylko survival: `new-shard survival … --sector <sx> <sz>`. Patrz `../BLUEPRINT-sektory.md`.
