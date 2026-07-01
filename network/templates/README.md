# Szablony trybów (per-mode overlay)

`new-shard` tworzy shard z bazy (`templates/_base` lub istniejący `servers/*`: `paper.jar`, `eula.txt`, `config/paper-global.yml`) i — jeśli istnieje `templates/<tryb>/` — **nakłada** na niego zawartość trybu. Dzięki temu nowy tryb to głównie katalog w `templates/`, zero zmian w kodzie core (routing, rejestr, handoff są mode-agnostyczne).

`deploy-core` wgrywa `core-paper.jar` i `mode-<tryb>.jar` do istniejących shardów **oraz** do `templates/<tryb>/plugins/`, żeby kolejne `new-shard` od razu miały świeże jary.

## `save-template` — back-sync z sharda

Po konfiguracji pluginów **na działającym shardzie** (np. w grze, przez konsolę lub edycję plików w `servers/survival-1/plugins/`) możesz wgrać zmiany z powrotem do szablonu, żeby kolejne `new-shard` od razu je dziedziczyły:

```bash
bash save-template.sh survival-1          # Linux
powershell -ExecutionPolicy Bypass -File save-template.ps1 -ServerName survival-1   # Windows
```

Skrypt bierze nazwę sharda (`<tryb>-<numer>`), wyciąga tryb (`survival-1` → `survival`) i kopiuje **`plugins/`** (tylko konfigi i dane pluginów — **bez** `.jar`, baz SQLite, logów, cache). Nie dotyka `world/`, `server.properties.extra`, `mode.conf` ani `config/` — te edytuj ręcznie w `templates/<tryb>/`.

Dla survivalu wszystkie sektory (`survival-1`, `survival-2`, …) współdzielą jeden szablon `templates/survival/` — wystarczy back-sync z dowolnego sharda tego trybu (najlepiej z sektora spawnowego `(0,0)`, gdzie konfigurujesz spawn w `CorePaper/config.yml`).

## Co może zawierać `templates/<tryb>/`

| Plik / katalog | Rola |
|---|---|
| `mode.conf` | Pasmo portów i limity graczy: `port-base`, `soft-cap`, `hard-cap`, opcjonalnie `display-name`. Czytane przez `new-shard` przy tworzeniu sharda. |
| `server.properties.extra` | Linie `klucz=wartość` nadpisujące/dodające do `server.properties` (np. `difficulty`, `level-seed`, `level-type`, `generator-settings`). |
| `plugins/` | Pluginy trybu (np. skyblock) + konfigi. `core-paper.jar` i `mode-<tryb>.jar` dokłada `deploy-core`. |
| `datapacks/` | Datapacki świata. |
| `config/` | Nadpisania configów Papera. |
| `world/` | Gotowy świat do skopiowania (opcjonalnie). |

## Istniejące szablony

| Katalog | Opis |
|---|---|
| `hub/` | Lobby po logowaniu. Pasmo portów od `25500`. |
| `survival/` | Klasyczny survival z **sektorami** — patrz sekcja poniżej. Pasmo od `25600`. |
| `oceanblock/` | Skyblock na oceanie (`level-type=flat` + plugin wyspy). Pasmo od `25700`. |

## Survival — sektory

Survival to jeden tryb, ale mapa jest podzielona na **sektory** (kwadraty, domyślnie 1000×1000). Każdy sektor to osobny shard (`survival-N`) z współrzędnymi `(sx, sz)` ustawianymi przy tworzeniu — **nie** z nazwy folderu.

Szablon `templates/survival/` wspólny dla wszystkich sektorów. Kluczowe w `server.properties.extra`:

- **`level-seed`** — **identyczny** na każdym shardzie survival. Dzięki temu teren jest ciągły przez granice sektorów. Zmieniasz seed raz w szablonie; istniejące shardy dostają go przy ponownym `new-shard` albo ręcznej edycji `server.properties`.
- **`view-distance`** / **`simulation-distance`** — `new-shard` ustawia domyślnie 8/6; można nadpisać w `server.properties.extra` (mniejsze wartości = mniej chunków przy gęstych sektorach).

Punkt spawnu nowych graczy: `plugins/CorePaper/config.yml` (`spawn.*` — współrzędne w **globalnym** świecie sektora `(0, 0)`).

Tworzenie shardów survival **zawsze z `--sector`**:

```bash
# Linux                                                    # Windows
bash new-shard.sh survival 1 2G --sector 0 0               # new-shard.ps1 -Mode survival -SectorX 0 -SectorZ 0
bash new-shard.sh survival 1 2G --sector 1 0               # new-shard.ps1 -Mode survival -SectorX 1 -SectorZ 0
# replika sektora (0,0) pod obciążenie:
bash new-shard.sh survival 1 2G --sector 0 0               # new-shard.ps1 -Mode survival -SectorX 0 -SectorZ 0
# opcjonalnie rozmiar sektora (spójny w całej sieci):
bash new-shard.sh survival 1 2G --sector 0 0 --size 1000   # ... -SectorSize 1000
```

Sektor `(0, 0)` to **sektor spawnowy** — tu trafia `/play survival`. Pozostałe sektory to sąsiednie regiony mapy; gracz przechodzi między nimi przez worldborder na skraju sektora.

Mechanika, repliki, `/ch`, `/spawn`: **[../../BLUEPRINT-sektory.md](../../BLUEPRINT-sektory.md)**. Start sieci: **[../START.md](../START.md)**.

## Dodanie nowego trybu (bez sektorów)

1. `mkdir templates/<tryb>` — minimum `mode.conf` + `server.properties.extra`; opcjonalnie plugin trybu (`mode-<tryb>` w `mc-core`).
2. Utwórz shardy:

   ```bash
   bash new-shard.sh <tryb> 1          # Linux
   new-shard.ps1 -Mode <tryb> -Count 1   # Windows
   ```

3. `deploy-core` (świeże jary) → start shardów → w grze `/play <tryb>`.

Przykład gotowy: `templates/oceanblock/`. Model danych per-tryb: **[../../BLUEPRINT-modes.md](../../BLUEPRINT-modes.md)**.

## Dodanie trybu z sektorami

Na razie sektory obsługuje tylko survival (ENV `ELCARTEL_SECTOR_*` w `core-paper`). Inny tryb z podziałem mapy wymagałby analogicznego rozszerzenia rdzenia — na dziś traktuj sektory jako feature survivalu, nie ogólny mechanizm szablonu.
