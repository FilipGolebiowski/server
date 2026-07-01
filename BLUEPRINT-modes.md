# Blueprint: wiele trybów (dane, struktura, multi-proxy)

**Rozszerza:** `BLUEPRINT.md`, `BLUEPRINT-kanaly.md`. Powód: tryby (`survival`, `oceanblock`, …) mają **różne ekonomie i mechaniki**, więc danych gracza nie wolno trzymać jako jednej globalnej całości. Dokument ustala podział danych, strukturę trybów, brak kolizji portów i gotowość na podział proxy.
**Wersja:** 1.0 · Data: 2026-06-22

---

## 1. Podział danych: global vs per-tryb (kluczowe)

| Zakres | Co | Gdzie (Mongo) | Klucz |
|---|---|---|---|
| **Konto (global)** | uuid, nick, premium, ranga/uprawnienia, kosmetyki, firstSeen/lastSeen/lastIp | `players` | `_id = uuid` |
| **Per-tryb (stan)** | ekwipunek, ender, statystyki, stan gry (exp/hp/food/gamemode/pozycje) | `profiles_<mode>` (osobna kolekcja per tryb) | `_id = uuid` |
| **Per-tryb (ekonomia)** | saldo | `economy_<mode>` (osobna kolekcja per tryb) | `_id = uuid` |
| **Auth** | hasło (Argon2id), 2FA, IP-binding | `auth` | `_id = uuid` (bez zmian) |

Zasada: **jedno konto gracza, ale osobne portfele i ekwipunki per tryb.** Saldo w survival ≠ saldo w oceanblock. Ranga/premium/kosmetyki są wspólne wszędzie.

> Dlaczego nie jedna ekonomia: tryby mają różną gospodarkę i mechaniki — wspólny portfel/ekwipunek powodowałby exploity (zarabiasz w jednym, wydajesz w drugim; itemy survival w oceanblock). Rozdział per-tryb to standard sieci wielotrybowych.

---

## 2. Handoff per-tryb

Lock, sygnał i wersja (BLUEPRINT-kanaly sek. 5–6) są kluczowane po **`<uuid>:<mode>`**, nie po samym uuid:

- `lock:profile:<uuid>:<mode>` — własność per-tryb,
- `handoff:<uuid>:<mode>` — sygnał gotowości danych trybu.

Skutki:

- **survival-1 → survival-2** (ten sam tryb): przenosi per-tryb dane survival (saldo, eq). Lock `<uuid>:survival`.
- **survival → oceanblock**: zapisuje dane survival, a na oceanblock ładuje **osobny** dokument z `profiles_oceanblock` (inny ekwipunek/staty; saldo z `economy_oceanblock`, często nowy). Locki różne → brak kontencji.

Konto global (`players`) jest jedno; shardy je odczytują (ranga) i odświeżają `lastSeen`.

---

## 3. Struktura trybów (templates)

```
network/templates/
  _base/                     # WSPÓLNE dla każdego sharda
    config/paper-global.yml  # forwarding secret (kanoniczny dla backendów)
    eula.txt
  survival/                  # tryb = templatka (survival też!)
    mode.conf                # port-base, softcap, hardcap, display
    server.properties.extra
    plugins/                 # pluginy trybu (opcjonalnie)
  oceanblock/
    mode.conf                # inne pasmo portów
    server.properties.extra
    plugins/
```

`new-shard -Mode <m>` = `_base` (wspólne) **+** `templates/<m>` (overlay trybu). **Survival jest teraz zwykłą templatką** — nie ma „wyróżnionego" serwera-bazy.

`mode.conf` (per tryb): `port-base`, `soft-cap`, `hard-cap`, `display-name`, opcjonalnie `economy` (namespace ekonomii; domyślnie nazwa trybu).

---

## 4. Porty bez konfliktów

- **Każdy tryb ma własne pasmo portów** (`port-base` w `mode.conf`): survival `25600+`, oceanblock `25700+`, kolejne tryby co 100.
- `new-shard` skanuje **wszystkie** `servers/*/server.properties` i bierze pierwszy wolny port **od `port-base` trybu w górę**.
- Pasma (modele nie nachodzą) **+** skan zajętości (instancje tego samego trybu nie kolidują) ⇒ kolizja portu niemożliwa, nawet przy wielu trybach i wielu shardach.

---

## 5. Multi-proxy (podział proxy w przyszłości)

Architektura jest już **gotowa na wiele proxy** (BLUEPRINT-kanaly sek. 8) — przy podziale dla load-balancingu:

- **Wiele Velocity za wspólnym Redisem.** Każde proxy ma własny `ShardWatcher`, który mirroruje ten sam rejestr shardów → wszystkie proxy widzą wszystkie shardy.
- **`core:auth` to broadcast** — odbiera je każde proxy, ale gracza przerzuca tylko to, które go trzyma (`getPlayer` zwraca pusto na pozostałych). Działa bez zmian przy N proxy.
- **Sesje w Redis** (`session:<uuid>`) — globalny widok „gdzie jest gracz".

Co dołożymy przy realnym podziale:

- **`ELCARTEL_PROXY_ID`** — identyfikator instancji proxy (do metryk, sesji, kierowania).
- **Współdzielona lista graczy / party / friends** między proxy (RedisBungee-Reloaded albo własny moduł na `core:proxy`).
- **Load-balancer/anycast** przed proxy (TCPShield + DNS) i **transfer-packet** (+ podpisany token sesji HMAC w cookie, BLUEPRINT-auth sek. 8) do przerzucania gracza między proxy.
- **Auth-state per-proxy** (gracz loguje się na tym proxy, na którym wszedł) — przy transferze między proxy honorujemy token sesji.

Żaden z tych punktów nie wymaga przeprojektowania rdzenia — to dołożenia.

---

## 6. Mechaniki per-tryb: modularne pluginy

Rdzeń (`core-paper`) = **fundament** wspólny dla wszystkich backendów: warstwa danych, handoff, ekonomia, auth (na limbo). Wystawia **API** `gg.elcartel.data.api.CoreApi` (`get().data()`, `mode()`, `shardId()`).

Funkcje **specyficzne dla trybu** to **osobne, lekkie pluginy** `mode-<tryb>` (`mode-hub`, `mode-survival`, `mode-oceanblock`, …):

- deklarują `depend: [CorePaper]` i pobierają rdzeń przez `CoreApi.get()` — **bez własnych połączeń** do Mongo/Redis;
- wgrywane **tylko na shardy swojego trybu** (`templates/<tryb>/plugins/`, kopiowane na shardy przez `new-shard`/`deploy-core`);
- nic nie bundlują (cienki jar) → **szybki start**, a tryb ładuje **tylko swoje** funkcje;
- trzymają swoje dane w **swoich kolekcjach** (np. `oceanblock_islands`) → baza nie zaśmieca się danymi nieużywanych trybów.

Tak „dużo rzeczy na jednych trybach, brak na innych" nie obciąża pozostałych — hub nie ładuje mechanik oceanblocka itd. Dane i tak są rozdzielone per-tryb (osobne kolekcje `profiles_<tryb>`, `economy_<tryb>`), więc pluginy trybów nie kolidują.

**Integracja Vault API**
Rdzeń `core-paper` natywnie rejestruje implementację `net.milkbowl.vault.economy.Economy` opartą na `EconomyRepository`. Zamiast pisać własne łączenia w pluginach trybów (np. sklepy, aukcje), każdy moduł pobiera Vaulta z `ServicesManager` Bukkita. Wszelkie wpłaty/wypłaty modyfikują bezpośrednio wieloserwerową ekonomię w MongoDB bez konieczności re-implementacji metod gospodarczych w każdym pluginie.

**Dodanie pluginu trybu:** nowy moduł Gradle `mode-<tryb>` (`compileOnly` core-data/common + paper-api, `plugin.yml` z `depend: [CorePaper]`) → `gradle build` → `deploy-core` kładzie jar do `templates/<tryb>/plugins` i na działające shardy tego trybu.

---

## 7. Mapa zmian w kodzie

- **core-data:**
  - `PlayerAccount` (kolekcja `players`, global) + `PlayerAccountRepository`.
  - `ModeProfile` (**osobna kolekcja per tryb** `profiles_<mode>`, `_id = uuid`) + `ModeProfileRepository.load(uuid, mode)/save` (routing po nazwie kolekcji, cache kolekcji).
  - `EconomyRepository` (**osobna kolekcja per tryb** `economy_<mode>`, `_id = uuid`) — saldo per-tryb, operacje **atomowe** (`$inc` / warunkowy withdraw); celowo **poza blobem handoffu**, by przelewy były bezpieczne niezależnie od tego, który shard trzyma gracza.
  - Lock/handoff w Redis pozostają kluczowane po `uuid:mode` (klucze globalne, nie nazwa kolekcji Mongo).
  - `ProfileLock` / `HandoffSignal` kluczowane po **String subject** (`uuid:mode`).
  - (usunięte: `PlayerProfile` / `PlayerProfileRepository` — zastąpione podziałem).
- **core-paper `ProfileService`:** świadomy trybu (`ELCARTEL_MODE`), ładuje/zapisuje `ModeProfile(uuid, mode)`, lock per `uuid:mode`, zapewnia istnienie `PlayerAccount`.
- **Bez zmian:** auth (limbo), rejestr/heartbeat shardów, routing/kanały — są mode-agnostyczne.

---

## Źródła / powiązane

- `BLUEPRINT.md` (fundament, kamienie), `BLUEPRINT-kanaly.md` (sharding/handoff/multi-proxy), `BLUEPRINT-auth.md` (auth, token sesji), `network/templates/README.md` (jak dodać tryb).
