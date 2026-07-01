# Blueprint: sektory (świat dzielony na podserwery) + GUI `/ch`

**Rozszerza:** `BLUEPRINT-kanaly.md` (sharding/handoff), `BLUEPRINT-modes.md` (dane per-tryb). Świat **survivala** dzielimy na **sektory** — kwadraty mapy (np. 1000×1000), z których każdy obsługuje osobny podserwer (shard). Przejście przez barierę na skraju sektora przenosi gracza na sąsiedni sektor; współrzędne są globalnie ciągłe (ten sam seed → płynny teren).
**Zakres:** na razie **tylko survival.** · **Wersja:** 1.0 · Data: 2026-06-23

---

## 1. Pojęcia

| Pojęcie | Znaczenie |
|---|---|
| **Sektor `(sx, sz)`** | kwadrat mapy o boku `size` (domyślnie 1000). Region świata: X ∈ [sx·size - size/2, (sx+1)·size - size/2), Z analogicznie. Dzięki przesunięciu o połowę, sektor `(0,0)` pokrywa się idealnie ze światowymi koordynatami 0, 0. |
| **Instancja sektora** | shard obsługujący dany sektor. Sektor może mieć **≥1 instancję** (repliki) dla obciążenia. |
| **Bariera** | granica sektora (worldborder). Próba przejścia = transfer na sąsiada. |
| **Sektor Spawnowy** | wydzielone sektory startowe (domyślnie te z koordynatami `(0,0)` lub z wymuszoną flagą `spawnSector`). To tu lądują nowi gracze komendą `/play` i z pomocą `/spawn`. |

Wszystkie instancje survivala mają **ten sam seed**, więc natura (teren) jest identyczna i ciągła przez granice. Każda instancja „posiada" tylko swój region — budowle gracza trzyma w swoim świecie; sąsiedni region należy do innego sharda.

---

## 2. Model danych

- **Współrzędne sektora w rejestrze** — `ShardInfo` ma pola `sx`, `sz` (zapisywane do `shard:<id>` przy rejestracji; heartbeat ich nie rusza). Dzięki temu każdy shard wie, które są sąsiadami i ile mają graczy.
- **Pozycja docelowa transferu** — `SectorDest` w Redis: klucz `sector:dest:<uuid>` = `world|x|y|z|yaw|pitch`, krótki TTL (12 s). Ustawia ją shard źródłowy tuż przed przerzutem; docelowy czyta i kasuje przy wejściu.
- Reszta (ekwipunek, ekonomia, staty) podąża istniejącym **handoffem per-tryb** (`BLUEPRINT-kanaly.md` sek. 5–6) — sektory to ten sam tryb `survival`, więc profil przenosi się automatycznie.

---

## 3. Mechanika przejścia

1. **Bariera.** Na starcie shardu `SectorService` ustawia worldborder wokół regionu sektora (środek = środek sektora, rozmiar = `size` + bufor). To „fizyczna" granica, której gracz nie przekroczy.
2. **Detekcja.** `PlayerMoveEvent` (liczony tylko przy zmianie bloku) sprawdza, czy gracz dotarł do skraju regionu (X ≥ maxX → wschód, X ≤ minX → zachód, Z → płd./płn.).
3. **Ostrzeżenie o granicy (BossBar).** Gdy gracz znajdzie się w odległości 20 bloków od granicy sektora, na górze ekranu pojawia się czerwony BossBar z informacją o dystansie do krawędzi ("Zbliżasz się do sektora: X bloków"). Pasek znika, gdy gracz się oddali lub zmieni sektor.
4. **Wybór instancji.** Wyznaczamy sąsiada `(nx, nz)` i z rejestru bierzemy jego instancje (shardy survival z `sx=nx, sz=nz`, świeże). Wybieramy **najmniej obciążoną** (preferencja: `joinable`, potem najmniej graczy). Gdy sąsiad jest pełny — gracz i tak trafia na **najmniej zapchaną instancję tego samego sektora** (to samo miejsce na mapie). Brak instancji sąsiada → komunikat „skraj świata" i odepchnięcie do środka.
5. **Transfer.** Zapisujemy `sector:dest` (te same globalne X/Z, przesunięte 2 bloki w głąb sąsiada) i wysyłamy plugin-message `elcartel:switch` z ID docelowego sharda → proxy łączy gracza. Na ekranie gracza pojawia się zielony komunikat ActionBar "Łączenie...".
6. **Wstawienie.** Na docelowym shardzie, w tym samym ticku po wejściu (po uprzednim asynchronicznym załadowaniu profilu w evencie `AsyncPlayerPreLoginEvent`) `SectorService` czyta `sector:dest` i natychmiast teleportuje gracza w wyznaczone miejsce. To całkowicie rozwiązuje problem chwilowego "spawnowania w starym miejscu". `ProfileService` odpowiednio blokuje wczytanie pozycji z profilu, upewniając się, że gracz nie zostanie odciągnięty od granicy w głąb sektora.

Współrzędne są zachowane (gracz był na X≈maxX, ląduje na X=maxX+2 w sąsiednim regionie), więc — przy tym samym seedzie — teren jest ciągły i przejście wygląda na całkowicie bezszwowe.

---

## 4. Równoważenie i repliki

Sektor z dużym ruchem dostaje **kilka instancji** (te same `sx,sz`, ten sam seed). Transfer i tak kieruje na najmniej obciążoną. To realizuje wymóg „gdy sektor pełny, prześlij na inny w tym samym miejscu na mapie".

> **Uwaga (repliki a budowle):** repliki tego samego sektora mają identyczną **naturę** (seed), ale **budowle graczy są osobne** na każdej instancji (każdy shard ma własne pliki świata). To akceptowalne dla rozładowania szczytów; jeśli budowanie ma być spójne między replikami, sektor trzymaj na **jednej** instancji (skaluj liczbą sektorów, nie replik) albo dołóż współdzielony backend świata (poza zakresem v1).

---

## 5. Uruchamianie sektorów

Sektor = zwykły shard survival **+** ENV współrzędnych. Nazewnictwo zostaje `survival-N` (żeby `modeOf` dla ban-trybu działał); tożsamość sektora jest w rejestrze, nie w nazwie.

```bash
# Linux                                   # Windows
bash new-shard.sh survival 1 2G --sector 0 0      # new-shard.ps1 -Mode survival -SectorX 0 -SectorZ 0
bash new-shard.sh survival 1 2G --sector 1 0      # new-shard.ps1 -Mode survival -SectorX 1 -SectorZ 0
bash new-shard.sh survival 1 2G --sector 0 1      # new-shard.ps1 -Mode survival -SectorX 0 -SectorZ 1
# replika zatłoczonego sektora (ten sam sx,sz - powstaje kolejny survival-N):
bash new-shard.sh survival 1 2G --sector 0 0      # new-shard.ps1 -Mode survival -SectorX 0 -SectorZ 0
```

Skrypt zapisuje do `start.sh`/`start.bat` `ELCARTEL_SECTOR_X/Z/SIZE` + `ELCARTEL_WORLD=world`. **Wszystkie** shardy survival muszą mieć **ten sam `level-seed`** — ustawiony w `templates/survival/server.properties.extra` (zmień, ale identycznie wszędzie). `--size <n>` zmienia bok sektora (domyślnie 1000; również musi być spójny). Po utworzeniu: `deploy-core` (świeży jar) i start shardów.

ENV sektora (gdy chcesz ręcznie): `ELCARTEL_SECTOR_X`, `ELCARTEL_SECTOR_Z`, `ELCARTEL_SECTOR_SIZE` (dom. 1000), `ELCARTEL_WORLD` (dom. `world`), `ELCARTEL_SECTOR_SPAWN` (domyślnie `true` tylko jeśli X=0 i Z=0).

---

## 6. Sektory startowe i /spawn

System rdzenia rozróżnia sektory, które pełnią rolę "Spawnu". Z definicji są to wszystkie serwery przypisane do siatki `(0,0)`. Gracz nowo dołączający do trybu domyślnie ląduje zawsze na jednym z serwerów startowych:
- **Komenda `/play <tryb>`**: Inteligentnie filtruje serwery i kieruje gracza na najmniej obciążony serwer, który ma przypiętą flagę Spawnu (np. omijając serwer w koordynatach `1,0`).
- **Komenda `/spawn`**: Działa w dwóch trybach. Jeśli gracz wpisze ją na Spawnie – serwer natychmiastowo przeteleportuje go pod konkretne koordynaty zdefiniowane w pliku `plugins/core-paper/config.yml`. Jeśli użyje jej będąc na bardzo dalekim sektorze brzegowym – nadpisze jego zapisany profil i przekaże prośbę do Velocity o natychmiastowy bezszwowy przerzut w locie na sektor spawnowy.
- **Konfigurowalny punkt odradzania**: Wchodząc z "czystym" profilem (lub używając `/spawn`), gracze są wyrzucani na X, Y, Z ze scentralizowanego pliku `config.yml` znajdującego się w module `core-paper`.
- **Globalna teleportacja (`/tp <gracz>`)**: Komenda proxy z inteligentnym autocomplete, która automatycznie przerzuca gracza na sektor/shard docelowego gracza. Wykorzystuje `tp:dest:<uuid>` (Redis) oraz system handoffu profilu do precyzyjnego umieszczenia tuż po zalogowaniu na maszynę docelową.

---

## 7. GUI `/ch` (wybór kanału/sektora)

Komenda **`/ch`** (alias `/sektory`) otwiera okno „SEKTORY" z kanałami bieżącego trybu: każdy przedmiot pokazuje **liczbę graczy** (`Graczy: X/cap`) z rejestru, bieżący kanał jest podświetlony („Jesteś połączony…"), a kliknięcie przełącza na wybrany podserwer (`elcartel:switch` → proxy → handoff). Wygląd i teksty w `messages.properties` (`sector.*`). 

Działa dla każdego shardowanego trybu, ale w przypadku rejonu posiadającego sektory, wyświetli wyłącznie podserwery będące sektorami spawnowymi. Gracze zmieniając kanały między takimi samymi sektorami za pośrednictwem tego GUI nie lądują na domyślnym środkowym Spawnie – mechanika profilów w tle wczyta ich ostatnie miejsce wylogowania z poprzedniej repliki i rzuci dokładnie w to samo wirtualne miejsce, osiągając gładką zmianę maszyny.

---

## 8. Mapa zmian w kodzie

- **core-data:** `ShardInfo` (+ `sx`,`sz`, `hasSector()`, `isSpawnSector()`); `SectorDest` (Redis `sector:dest:<uuid>`, set/peek/take); `CoreData.sectorDest()`.
- **core-paper:** `SectorService` (worldborder-bariera, detekcja); `SpawnCommand` (natychmiastowy teleport z config.yml lub przerzut przez Proxy); `SectorMenu` (`/ch` GUI filtrujące tylko sektory spawnowe); `ProfileService` pomija pozycję z profilu gdy czeka `sector:dest` lub wczytuje domyślną lokację z nowego `config.yml`.
- **core-velocity:** odbiór kanału `elcartel:switch` (w tym parsowanie żądania SPAWN) → logowanie na najmniej obciążony sektor spawnowy (kierowanie z `ShardRouter.java`).
- **network:** `new-shard.sh/.ps1` `--sector sx sz [--size n]`; `templates/survival/server.properties.extra` wspólny `level-seed`.

---

## 9. Ograniczenia / dalej

- Na razie **tylko survival** (gating po ENV sektora; inne tryby działają jak dotąd).
- Repliki sektora dzielą naturę, ale nie budowle (sek. 4).
- Dalej: pas „przygraniczny" z podglądem sąsiada (chunki zza bariery), współdzielony storage świata dla replik, automatyczne skalowanie liczby instancji sektora wg obciążenia.

---

## Źródła / powiązane

- `BLUEPRINT-kanaly.md` (sharding, handoff, multi-proxy), `BLUEPRINT-modes.md` (dane per-tryb), `network/ADMIN.md` (operacje), `network/START.md` (start).
