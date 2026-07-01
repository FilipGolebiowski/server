# Blueprint: kanały, sharding trybów i synchronizacja gracza

**Rozszerza:** `BLUEPRINT.md` (v1.0) — kamienie milowe M1 (core-data) i M3 (routing/kolejka/sync).
**Cel:** sieć utrzymująca **setki tysięcy graczy jednocześnie**, gdzie każdy tryb dzieli się na wiele identycznych podserwerów („kanałów"), a **pełny stan gracza (dosłownie wszystko: ekwipunek, ender chest, exp, efekty, ekonomia, rangi, statystyki) podąża za graczem** przy zmianie podserwera.
**Wersja:** 1.0 · Data: 2026-06-22

> Decyzje z tego dokumentu są wiążące dla implementacji `core-data` i `core-velocity`. Najpierw spec (ten dokument), potem kod.

---

## 1. Pojęcia (żeby nie mylić warstw)

| Pojęcie | Znaczenie |
|---|---|
| **Tryb** | Rodzaj rozgrywki (survival, skyblock, …). Kategoria logiczna. |
| **Kanał** | Egzemplarz trybu widoczny dla gracza: „Survival #1", „Survival #2". 1 kanał = 1 shard. |
| **Shard** | Jedna instancja serwera (Paper/Folia) obsługująca jeden kanał. Każdy shard ma **własny świat**. |
| **Profil** | Komplet danych gracza w MongoDB (globalne + stan gry). To on wędruje między shardami. |
| **Proxy** | Velocity. Przy tej skali jest ich **wiele** (multi-proxy), spiętych wspólnym Redisem. |
| **Handoff** | Bezpieczne przekazanie własności profilu z sharda A do sharda B przy zmianie kanału. |

Kluczowa zasada: **shardy tego samego trybu są równorzędne i bezstanowe wobec profilu** — autorytatywne dane gracza żyją w MongoDB/Redis, a nie „w pamięci sharda". Shard to tylko chwilowy wykonawca, który dzierży profil pod lockiem.

---

## 2. Topologia (setki tysięcy graczy)

```
                              Gracze (setki tysięcy)
                                      │
                            [ Cloudflare DNS / Anycast ]
                                      │
                            [        TCPShield         ]   filtr L4/L7, ukrycie origin
                                      │
        ┌──────────────┬─────────────┼─────────────┬──────────────┐
     VELOCITY #1     VELOCITY #2   VELOCITY #3   ...           VELOCITY #N     (po ~3–5k graczy/proxy)
        └──────────────┴─────────────┼─────────────┴──────────────┘
                                      │  wspólny Redis = jedna sieć (lista graczy, sesje, pub/sub, party)
                                      │
                      sieć PRYWATNA (wszystkie proxy widzą wszystkie shardy)
   ┌─────────────┬───────────────┬───────────────┬───────────────┬─────────────┐
 LIMBO ×M      HUB ×K     SURVIVAL #1..#a    SKYBLOCK #1..#b   TRYB X #1..#c     Paper/Folia (dedyki AX)
(auth/antybot)             └── kanały trybu ──┴── kanały trybu ──┴── kanały ─────┘
                                      │  sieć PRYWATNA
                       ┌──────────────┴───────────────┐
                       │  MongoDB (SHARDED CLUSTER)    │   profile (klucz = uuid, hashed)
                       │  Redis (CLUSTER)              │   rejestr shardów, locki, handoff, pub/sub, sesje
                       └──────────────────────────────┘
```

Różnice względem bazowego blueprintu: **wiele proxy** zamiast jednego (+ wspólny Redis jako „spina"), **sharded MongoDB** zamiast samego replica setu, **Redis w trybie cluster**. Reszta zasad bezpieczeństwa (tylko proxy publiczne, backendy w sieci prywatnej, modern forwarding) bez zmian.

---

## 3. Rejestr shardów i heartbeat

Każdy shard po starcie **sam rejestruje się w Redisie** i co 1–2 s wysyła heartbeat. Proxy nie ma sztywnej listy — odkrywa shardy dynamicznie (to też daje autoscaling z sekcji 11).

Model w Redisie:

```
shards:index                      -> SET wszystkich shardId
shards:mode:survival              -> SET { "survival-1", "survival-2", ... }
shard:survival-1                  -> HASH {
     mode: "survival",
     addr: "10.0.0.21:25570",     # prywatne IP backendu
     state: "OPEN",               # OPEN | FULL | DRAINING | DOWN
     players: 150,
     softCap: 180, hardCap: 200,
     tps: 19.8, mspt: 38,
     heartbeat: 1750600000000     # epoch ms
}
```

Reguły:

- **Świeżość:** jeśli `now - heartbeat > 5 s`, proxy traktuje shard jako `DOWN` i przestaje na niego routować (nie czekamy na „ładny" shutdown przy crashu).
- **Stany:** `OPEN` (przyjmuje), `FULL` (osiągnięty softCap — nie przyjmuje nowych, istniejący grają), `DRAINING` (planowane wyłączenie — wypycha graczy, zero nowych), `DOWN` (martwy).
- **Źródło prawdy o obecności gracza:** `session:<uuid> -> { proxy, shard, ts }` (kto gdzie jest — używane do „dołącz do znajomego" i do wykrycia podwójnego wejścia).

---

## 4. Routing i kanały (UX + logika)

Wejścia do routingu:

1. **`/play survival`** → auto-wybór **najmniej obciążonego** sharda `OPEN` (min `players`, przy remisie wyższy `tps`).
2. **Wybór kanału (GUI):** lista „Survival #1 (150/200)", „Survival #2 (120/200)"… — klik = wejście na konkretny shard, jeśli `OPEN`.
3. **„Dołącz do znajomego":** lookup `session:<uuid>` znajomego → ten sam shard (jeśli jest miejsce; inaczej propozycja kolejnego).
4. **Kolejka** (`core:queue`): gdy wszystkie shardy trybu `FULL` — gracz w kolejce, wpuszczany gdy zwolni się miejsce lub autoscaler dołoży shard.

Algorytm auto-wyboru (uproszczony):

```
kandydaci = shardy[mode] gdzie state==OPEN i players<softCap i swieży_heartbeat
jeśli pusto: -> kolejka (lub komunikat „pełne, dokładamy serwery")
wybierz min(players); remis -> max(tps); remis -> losowo (rozkład)
```

Balansowanie jest **miękkie**: gracze NIE są przerzucani automatycznie między shardami dla idealnego rozkładu (to psułoby rozgrywkę). Rozkład wyrównuje się naturalnie przez nowe wejścia + autoscaling.

---

## 5. Protokół handoffu pełnego profilu (rdzeń systemu)

Problem do rozwiązania: gdy gracz przechodzi `survival-1 → survival-2`, jego **pełny profil** musi trafić na nowy shard bez **rollbacku/duplikacji** (klasyczny bug: stary shard zapisuje dane *po* tym, jak gracz już coś zmienił na nowym → nadpisanie nowszych danych starszymi).

Rozwiązanie: **lock na UUID + zapis-przed-transferem + wersjonowanie + flaga gotowości w Redisie.** MongoDB jest autorytatywne; Redis koordynuje.

### Klucze koordynacyjne (Redis)

```
lock:profile:<uuid>   -> wartość = shardId właściciela; SET ... NX PX 15000  (tylko właściciel pisze)
handoff:<uuid>        -> HASH { version, fromShard, toShard, ts }; krótki TTL (np. 30 s)
profile:<uuid>:ver    -> licznik wersji profilu (monotoniczny, INCR przy każdym zapisie)
```

**Lock jako lease (ważne):** shard-właściciel trzyma `lock:profile` przez **całą** sesję i **odnawia jego TTL przy każdym heartbeacie** (np. renew co 2 s, TTL 15 s). Dzięki temu lock żyje dokładnie tak długo, jak żyje shard: w normalnej grze nigdy nie wygaśnie, a po crashu nikt go nie odnawia → wygasa w ≤15 s i staje się przejmowalny. To domyka „lock wisi po crashu" bez ryzyka, że padnie w trakcie rozgrywki.

### Przebieg (happy path)

```
Gracz na A (survival-1) prosi o zmianę na B (survival-2)
        │
 1. core-velocity sprawdza: B == OPEN i players<softCap?  (nie → komunikat/kolejka)
        │
 2. Proxy -> A: "PREPARE_HANDOFF <uuid> -> B"
        │
 3. A: zatrzymuje interakcje gracza (zamraża), serializuje PEŁNY profil,
       INCR profile:<uuid>:ver  -> v
       zapis profilu do MongoDB (writeConcern majority)  [A wciąż trzyma lock:profile]
       SET handoff:<uuid> = {version=v, fromShard=A, toShard=B}
       ZWALNIA lock:profile:<uuid>     <-- od tej chwili A już NIE pisze
       ACK do proxy
        │
 4. Proxy: przełącza połączenie gracza A -> B (Velocity server switch)
        │
 5. B (w AsyncPlayerPreLoginEvent - przed wejściem gracza):
       czeka aż uda się SET lock:profile:<uuid> = B NX (z timeoutem ~5 s)
       czyta handoff:<uuid>, ładuje profil z MongoDB i buforuje go
       warunek spójności: załadowana wersja >= handoff.version (inaczej krótki retry)
       DEL handoff:<uuid>; oznacza session:<uuid> = {proxy,B}
 6. B (w PlayerJoinEvent):
       natychmiastowo nakłada zbuforowany ekwipunek/ender/exp/efekty/gamemode + dane globalne na gracza bez żadnego opóźnienia (bez zamrażania)
```

Sekwencja w skrócie: **A zapisuje i oddaje lock ZANIM gracz wejdzie na B; B niczego nie ładuje, dopóki nie zdobędzie locka.** To eliminuje wyścig „late write".

---

## 6. Spójność i edge-case'y

| Sytuacja | Ryzyko | Mitygacja |
|---|---|---|
| **Late write** (A pisze po transferze) | rollback/dupe ekwipunku | A zwalnia lock **po** zapisie i **przed** transferem; po zwolnieniu locka A nie pisze. B ładuje dopiero po zdobyciu locka. |
| **Crash A w trakcie save** | utrata kilku sekund postępu | `lock:profile` ma TTL → wygasa; B ładuje ostatni *majority* zapis. Bounded loss ograniczamy autosave'em (sekcja 9) i save-on-critical-action. |
| **Podwójne wejście** (ten sam uuid na 2 shardach) | rozjazd/dupe | Pojedyncza własność przez `lock:profile NX`. B nie startuje sesji bez locka. `session:<uuid>` wykrywa duplikat → kick starszej sesji. |
| **Szybki hop A→B→C** | załadowanie starszej wersji | Wersjonowanie `profile:<uuid>:ver`: B/C wymagają `loaded.version >= handoff.version`, inaczej retry. |
| **B pełny/`DOWN` w chwili transferu** | gracz „w próżni" | Pre-check w kroku 1; jeśli B padł między krokiem 1 a 4 — failover na inny shard `OPEN` lub powrót na A/hub z komunikatem. |
| **Split-brain (partycja Redis)** | dwa locki | Redis cluster z quorum; locki krótkie + TTL; przy braku pewności B woli odmówić (fail-safe: gracz zostaje, gdzie jest). |
| **Lock „wisi" po crashu** | gracz nie może wejść | Lease: TTL + odnawianie przy heartbeacie (sekcja 5) → po crashu lock wygasa w ≤15 s. Watchdog czyszczący locki shardów `DOWN` jako backup. |
| **Lock wygasa w trakcie długiej sesji** | inny shard przejmuje aktywnego gracza | Właściciel odnawia TTL locka przy każdym heartbeacie (lease) — lock nie wygaśnie, dopóki shard żyje. |

Zasada nadrzędna: **w razie wątpliwości nie ruszamy danych** (fail-safe). Lepiej zostawić gracza na obecnym shardzie z komunikatem „spróbuj za chwilę" niż zaryzykować dupe.

---

## 7. Model danych (MongoDB sharded)

### Co dokładnie wędruje (pełny profil)

- **Stan gry:** ekwipunek, ender chest, zbroja/offhand, exp/poziom, zdrowie, głód/saturacja, efekty mikstur, gamemode, ostatnia pozycja **per świat** (mapowanie `world -> {x,y,z,yaw,pitch}` — pozycja jest specyficzna dla świata sharda).
- **Dane globalne:** ekonomia (saldo), ranga/grupy uprawnień, statystyki, kosmetyki, ustawienia, kary (z `punishments`).

### Kolekcje i klucz shardingu

| Kolekcja | Klucz shardingu | Uwagi |
|---|---|---|
| `players` | `{ _id: "hashed" }` (uuid) | **Konto global**: nick, premium, ranga, kosmetyki — wspólne dla wszystkich trybów. |
| `profiles_<mode>` / `economy_<mode>` | `{ _id: "hashed" }` (uuid) | **Osobna kolekcja per tryb**: stan gry (ekwipunek/ender NBT→base64, statystyki) + saldo. survival/oceanblock w odrębnych kolekcjach; lock/handoff w Redis per `uuid:mode`. Patrz `BLUEPRINT-modes.md`. |
| `economy`, `punishments`, `products`, `orders`, `deliveries` | wg blueprintu | `economy` można osadzić w `players`. |

- **Serializacja ekwipunku:** natywny NBT przedmiotów → bajty → base64 w dokumencie. Zawsze z polem `schemaVersion` (migracje na poziomie aplikacji).
- **writeConcern `majority`** dla zapisu profilu przy handoffie (gwarancja trwałości przed oddaniem locka).
- **Po co hashed shard key:** uuid jako hashed klucz rozkłada 100k+ profili równomiernie na shardy Mongo, brak „hot shardu". Zapytania zawsze po `uuid` (targeted query, nie scatter-gather).

> Redis jest **cache + koordynacja**, nie magazyn prawdy. Profil w cache (`cache:profile:<uuid>`) skraca load przy handoffie, ale autorytetem pozostaje MongoDB.

---

## 8. Multi-proxy (wiele Velocity)

Velocity **nie** synchronizuje proxy natywnie — robi to wspólny Redis. Przy 100k+ graczy potrzeba dziesiątek proxy (po ~3–5k każde).

Co musi być spójne między proxy: globalna lista graczy i licznik, cross-proxy wiadomości/komendy, party/friends, `session:<uuid>`.

Opcje realizacji:

1. **Gotowiec:** RedisBungee-Reloaded (wspiera Velocity) lub MultiProxySync / Chocolate / Bridger. Szybki start, mniej kontroli.
2. **Własne w `core-velocity`** (rekomendowane docelowo): te same kanały Redis pub/sub, pełna kontrola i spójność z resztą core'a. Na start można oprzeć się o gotowca i wymienić później.

**Zmiana kanału a multi-proxy:** ponieważ **wszystkie proxy widzą wszystkie shardy** (sieć prywatna), zmiana kanału to zwykły server-switch na *tym samym* proxy — handoff danych (sekcje 5–6) działa identycznie niezależnie od proxy, bo dane są w wspólnym Mongo/Redisie.

**Transfer packet + Cookies (1.20.5+, są w 1.21.11):** służą do przenoszenia klienta **między proxy** (balansowanie/failover proxy, anycast), nie do przenoszenia ekwipunku. Cookie ma limit **5 KiB** — za mało na profil; używamy go wyłącznie na **podpisany token sesji** (żeby docelowe proxy zaufało sesji bez tarcia). Pełny profil zawsze przez bazę. Token cookie **musi być podpisany** (HMAC) i weryfikowany po stronie docelowej — inaczej podatność na podszycie.

---

## 9. Operacje: autosave, drain, monitoring

- **Autosave** profilu co N sekund (np. 60 s) + **save-on-critical-action** (handel, śmierć, duża transakcja ekonomiczna) — ogranicza utratę danych przy crashu do sekund.
- **DRAINING:** przy planowanym restarcie shard wchodzi w `DRAINING` (zero nowych, istniejący wypychani handoffem na inny kanał), dopiero potem stop.
- **Metryki specyficzne dla tej warstwy** (poza standardem z blueprintu): liczba/latencja handoffów, **nieudane handoffy**, kontencja locków (`lock:profile` waiting), wiek heartbeatów shardów, długość kolejek per tryb, rozjazd liczby graczy między shardami.
- **Alerty:** wzrost nieudanych handoffów, lock contention, shard bez heartbeatu, kolejka rośnie szybciej niż autoscaling dokłada shardy.

---

## 10. Silnik: Paper vs Folia

Przy setkach tysięcy graczy liczba shardów jest pochodną pojemności instancji.

| | **Paper** | **Folia** |
|---|---|---|
| Realna pojemność/instancję | ~150–500 | ~1000+ (mocny CPU 16+ rdzeni; gracze rozproszeni po mapie) |
| Liczba shardów na 100k | ~200–660 | ~100 lub mniej |
| Plugin/core | standardowy model wątkowy | **musi być pisany pod regiony Folii** (RegionScheduler/GlobalScheduler) — inaczej crash/błędy |
| Dojrzałość (2026) | produkcyjny standard | aktywnie rozwijana, ale wymaga dyscypliny wątkowej |

**DECYZJA: Paper**, twardy cap **150–200 graczy/shard**. Folię odrzucamy — `core-paper` zostaje przy standardowym modelu wątkowym (prościej, pełna zgodność pluginów, brak dyscypliny regionów). Świadoma konsekwencja: **więcej shardów** (sekcja 11) i więcej międzyshardowych przeskoków przy bardzo dużej skali — akceptowane na rzecz prostoty implementacji.

> Cała pozostała architektura (kanały, handoff, multi-proxy, model danych) jest **niezależna** od tego wyboru — pinujemy tylko silnik i pojemność sharda.

---

## 11. Skalowanie i szacunki zasobów

Przykładowy „obraz docelowy" dla **~100k jednocześnie** (rząd wielkości, nie zamówienie):

| Komponent | Ilość (orientacyjnie) | Uwagi |
|---|---|---|
| Velocity (proxy) | 20–40 | ~3–5k graczy/proxy; wspólny Redis; za TCPShieldem |
| Shardy trybów | **~500–670** (Paper @ 150–200/shard) | autoskalowane wg obciążenia; przy bardzo wysokiej skali to setki instancji |
| Limbo | 5–15 | lekkie, kluczowe dla anti-bot przy dużym ruchu |
| Hub | 10–30 | rozkład wejść po auth |
| MongoDB | klaster **sharded**: 3–6 shardów × replica set(3) + config servers + mongos | klucz hashed(uuid) |
| Redis | **cluster** 6+ węzłów | locki/rejestr/sesje; pub/sub z uwagą (niżej) |

**Reality-check (uczciwie):** setki tysięcy graczy *jednocześnie* to pułap najwyższej półki w całym ekosystemie Minecrafta — to skala operacyjnie i kosztowo ekstremalna (dziesiątki–setki maszyn, zespół ops 24/7). **Rekomendacja:** budować architekturę „multi-proxy + sharded" **od początku** (tak, by nie przepisywać), ale **startować mniejszą skalą** (kilka proxy, kilkanaście shardów) i skalować **poziomo** — projekt z tego dokumentu to umożliwia bez zmian fundamentów.

**Redis Cluster a pub/sub:** klasyczny pub/sub w trybie cluster ma ograniczenia (wiadomości nie propagują się jak w pojedynczym węźle). Użyć **sharded pub/sub** (Redis 7+) albo dedykowanego, nieklastrowanego węzła Redis na pub/sub. Do uwzględnienia w `core-data`.

---

## 12. Autoscaling shardów

- **Skalowanie w górę:** monitor widzi wszystkie kanały trybu blisko `softCap` lub rosnącą kolejkę → uruchamia nowy shard (z szablonu), który **sam rejestruje się w Redisie** → proxy zaczyna na niego routować automatycznie (sekcja 3). Zero ręcznej rekonfiguracji proxy.
- **Skalowanie w dół:** mała obłożoność → wybrany shard `DRAINING` → po opróżnieniu stop i wyrejestrowanie.
- **Szablon sharda:** obraz/preset (config + start) parametryzowany `shardId`, `mode`, `addr`. Spójny z `setup` sieci.

**Dodawanie trybów jest tanie.** Rdzeń (routing, rejestr, handoff) jest **mode-agnostyczny** — „tryb" to tylko string `ELCARTEL_MODE`. Nowy tryb (np. `oceanblock`) = katalog `network/templates/<mode>/` z jego światem/pluginami (`server.properties.extra`, `plugins/`, `datapacks/`, `world/`) + `new-shard -Mode <mode> -Count N`. Zero zmian w kodzie core; proxy, `/play <mode>` i `/channels <mode>` obsłużą go automatycznie (dynamiczna rejestracja serwerów z rejestru Redis).

---

## 13. Wpływ na moduły core (mapowanie na M1/M3)

- **`core-data` (M1):** repozytoria `players` (global) + `profiles_<mode>`/`economy_<mode>` (per-tryb, osobne kolekcje), serializacja NBT, wersjonowanie, cache w Redisie, prymitywy locka (`lock:profile:<uuid>:<mode>` NX+TTL lease), klienty pod Mongo sharded + Redis cluster, abstrakcja pub/sub (z trybem sharded).
- **`core-velocity` (M3):** rejestr/heartbeat shardów, routing (auto + kanały + join-friend), kolejka, orkiestracja handoffu (PREPARE_HANDOFF/ACK/switch), multi-proxy sync (sesje/lista/party).
- **`core-paper` (M3/M4):** strona shardowa handoffu (zamrożenie, serializacja, zapis+oddanie locka; po stronie B — czekanie na lock, load, aplikacja), autosave, save-on-critical-action, stany OPEN/FULL/DRAINING.
- **Nowe/rozszerzone kanały Redis:** `core:shard` (rejestr/heartbeat), `core:handoff` (sygnały), istniejące `core:queue`, `core:player`, `core:proxy`.

---

## 14. Ryzyka i rekomendacje

- **Handoff to najtrudniejszy fragment** — wymaga testów na wyścigi (szybkie hopy, crash w trakcie, podwójne wejście). W M3 zrobić zestaw testów chaosu (kill sharda w środku handoffu).
- **Folia = decyzja fundamentalna** (przenika cały `core-paper`). Podjąć przed kodowaniem logiki gry.
- **Bounded data loss** jest nieunikniony przy crashu (sekundy) — akceptujemy i ograniczamy autosave'em; brak pojedynczego punktu, który gwarantuje zero strat bez ogromnego kosztu.
- **Start mniejszy, skaluj poziomo** — architektura gotowa na 100k, ale launch na ułamku tej skali.
- **Redis blisko proxy/shardów** (ten sam DC) — latencja Redisa wprost przekłada się na latencję handoffu i spójność listy graczy.

---

## 15. Kolejne kroki (po akceptacji tego dokumentu)

1. **Silnik: Paper** (zdecydowane), cap **150–200/shard**. Folia odrzucona — prostszy model pluginów.
2. **M0 → M1 (kod):** najpierw szkielet Gradle + puste pluginy „hello" na Paper i Velocity (M0), potem `core-data` — profil + serializacja + wersjonowanie + lock + cache (fundament handoffu).
3. **M3 (kod):** rejestr/heartbeat shardów → routing + kanały → handoff end-to-end → multi-proxy sync → testy chaosu.
4. Szablon sharda + autoscaling spięty z `setup` sieci.

---

## Źródła

- [PaperMC — Folia](https://github.com/PaperMC/Folia) · [Folia FAQ / docs](https://docs.papermc.io/folia/) · [Folia vs Paper 2026](https://space-node.net/blog/folia-vs-paper-multithreading-minecraft-server-2026)
- [RedisBungee multi-proxy (przegląd)](https://guide.astroworldmc.com/redisbungee-multi-proxy-guide) · [MultiProxySync](https://modrinth.com/plugin/multiproxysync) · [Chocolate](https://github.com/GiansCode/Chocolate) · [Bridger](https://github.com/thatgoofydev/Bridger) · [party-system](https://github.com/Dominik48N/party-system)
- [Transfer Packets — TCPShield](https://docs.tcpshield.com/miscellaneous/transfer-packets) · [Protokół / cookies — Minecraft Wiki](https://minecraft.wiki/w/Java_Edition_protocol/Packets) · [Velocity server compatibility](https://docs.papermc.io/velocity/server-compatibility/)
- [MongoDB — Sharding](https://www.mongodb.com/docs/manual/sharding/) · [Hashed shard key](https://www.mongodb.com/docs/manual/core/hashed-sharding/) · [Redis Cluster pub/sub](https://redis.io/docs/latest/develop/interact/pubsub/)
