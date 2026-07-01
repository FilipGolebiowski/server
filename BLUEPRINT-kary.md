# Blueprint: kary (moderacja) + uprawnienia

**Rozszerza:** `BLUEPRINT.md` (M4), `BLUEPRINT-modes.md` (dane per-tryb). Kary mają **dwa zakresy**: cała sieć (`network`) i pojedynczy tryb (`<mode>`). Uprawnienia obsługuje zewnętrzny **LuckPerms** (nie własny system).
**Wersja:** 1.1 · Data: 2026-06-23

---

## 1. Model danych

Jedna globalna kolekcja **`punishments`** (Mongo). Kara to dokument:

| Pole | Znaczenie |
|---|---|
| `_id` | losowy uuid kary |
| `uuid` | ukarany gracz (indeks) |
| `type` | `BAN` \| `MUTE` \| `KICK` \| `WARN` |
| `scope` | `network` (cała sieć) **albo** nazwa trybu (np. `survival`) |
| `reason` | powód |
| `byName` | kto nadał (nick admina lub `Console`) |
| `createdAt` / `expiresAt` | czas nadania / wygaśnięcia (`0` = na stałe) |
| `active` | `true` dopóki nie zdjęta (`/unban`, `/unmute`) |

Indeksy: `uuid` oraz złożony `(uuid, type, scope, active)`. Wygasanie jest **leniwe** — zapytanie filtruje `expiresAt = 0 OR expiresAt > teraz`, więc wygasłe kary po prostu przestają obowiązywać (rekord zostaje jako historia).

**Efektywna kara** = najpierw `network` (priorytet), potem dany tryb. Czyli ban sieciowy obejmuje wszystkie tryby; ban `survival` blokuje tylko survival.

---

## 2. Zakresy — dwie rodziny komend

| Zakres | Komenda | Co blokuje | Gdzie egzekwowane |
|---|---|---|---|
| **Tryb** (domyślny) | `/ban gracz` | wejście na shardy **danego trybu** | proxy, przy wejściu na shard trybu |
| **Sieć** | `/banproxy gracz` | wejście na **cały** serwer | proxy, przy logowaniu |

Reguła: **komenda bez sufiksu = ban na tryb, komenda z sufiksem `proxy` = ban na całą sieć.** Tak samo dla reszty: `/mute` ↔ `/muteproxy`, `/kick` ↔ `/kickproxy`, `/warn` ↔ `/warnproxy`, `/unban` ↔ `/unbanproxy`, `/unmute` ↔ `/unmuteproxy`.

Tryb dla wariantu bez sufiksu = **tryb, na którym stoi nadający** (staff moderujący survival → ban na survival). Można nadpisać `-t <tryb>` (przydatne z konsoli/huba). Gracz z banem na `survival` dalej gra na `oceanblock`; `/banproxy` = nie wejdzie wcale.

---

## 3. Egzekwowanie

- **Ban sieciowy** — `LoginEvent` na proxy (po autoryzacji, z finalnym UUID): aktywny `BAN/network` → odmowa wejścia z ekranem („Zostałeś zbanowany… Powód… Wygasa…”).
- **Ban trybu** — `ServerPreConnectEvent` na proxy: tryb docelowego sharda wyprowadzamy z **nazwy serwera** (`survival-1` → `survival`; `limbo`/serwery statyczne pomijamy). Aktywny `BAN/<mode>` → połączenie odrzucone + komunikat; gracz zostaje tam, gdzie był.
- **Mute** — na **shardzie**, na czacie. Każdy shard trzyma cache wyciszeń (`MuteService`): na wejściu gracza ładuje `MUTE/network` ∥ `MUTE/<thisMode>` do pamięci, a `AsyncPlayerChatEvent` tylko sprawdza pamięć (czat jest gorącą ścieżką — bez zapytań do Mongo na każdą wiadomość). Zmiany na żywo (admin wycisza/odcisza gracza online) lecą kanałem Redis **`core:punish`** (uuid) → shard trzymający gracza przeładowuje jego status.
- **Kick** — natychmiastowy rozłącz (jeśli online) + rekord `KICK` w historii.
- **Warn** — tylko rekord + komunikat do gracza (jeśli online).

Operacje proxy (`disconnect`, `sendMessage`) są thread-safe, więc komendy liczą się **asynchronicznie** (Mongo poza wątkiem zdarzeń); na shardzie przeładowanie cache mute idzie przez scheduler async.

---

## 4. Komendy (na proxy)

Rejestrowane na Velocity, więc działają **globalnie** (admin użyje ich z dowolnego trybu — proxy przechwytuje komendę). Wspólna składnia:

```
/<komenda> <gracz> [czas] [powód...] [pokaz|cichy]      # tryb nadawcy
/<komenda>proxy <gracz> [czas] [powód...] [pokaz|cichy] # cała sieć
```

- **Czas** (opcjonalny, pierwszy po nicku): `30m`, `2h`, `7d`, `1w`, `perm`. Brak = na stałe.
- **Rozgłaszanie** (opcjonalny, ostatni token): `pokaz` (lub `bc`/`-b`) = ogłoś karę na czacie wszystkim; `cichy` (lub `silent`/`-s`) = tylko dla nadającego. **Domyślnie `pokaz`.**
- **`-t <tryb>`** — nadpisuje tryb dla wariantu bez sufiksu (np. ban na survival z poziomu huba/konsoli).
- **Auto-uzupełnianie (TAB):** Komendy wspierają dynamiczne sugestie argumentów. Dla wariantu proxy proponowani są wszyscy gracze z sieci, dla wariantu zwykłego - wyłącznie gracze z danego trybu. Kolejne argumenty (np. powód, czas) są podpowiadane dynamicznie (np. `<czas_np_1h_lub_perm>`).
- Cel po nicku — gracz musi być online albo wcześniej wejść (jest w `players`).

Komendy: `ban`/`banproxy`, `unban`/`unbanproxy`, `mute`/`muteproxy`, `unmute`/`unmuteproxy`, `kick`/`kickproxy`, `warn`/`warnproxy`.

Przykłady:

```
/ban Gracz 7d griefing            # ban na trybie, na którym stoisz, na 7 dni, ogłoszony
/banproxy Gracz cheaty            # ban na całą sieć, na stałe, ogłoszony
/ban Gracz 1d spam cichy          # ban na tryb, bez ogłaszania na czacie
/ban Gracz -t oceanblock 3d xray  # ban na oceanblock z dowolnego miejsca
/mute Gracz 1h spam               # mute na trybie nadawcy
/unbanproxy Gracz                 # zdjęcie bana sieciowego
```

Różnica `/kick` vs `/kickproxy`: `kickproxy` rozłącza z sieci; `kick` rozłącza tylko, gdy gracz jest na Twoim trybie (kick z trybu).

---

## 5. Konfigurowalne wiadomości (`messages.properties`)

Wszystkie teksty kar są w pliku **`network/messages.properties`** (edytowalnym), z wbudowanymi domyślnymi wartościami — działa nawet bez pliku. Wczytuje go `Messages` (core-data) tymi samymi ścieżkami co `Config` (z katalogu serwera w górę), więc proxy i shardy widzą ten sam plik.

- **Kody kolorów `&`** (`&c` czerwony, `&7` szary, `&l` pogrubienie, `&r` reset) — tłumaczone na komponenty Adventure (`LegacyText`).
- **Placeholdery:** `{player}`, `{scope}`, `{reason}`, `{duration}`, `{by}` (oraz `{cmd}`/`{perm}` w komunikatach pomocniczych). `\n` = nowa linia (ekran bana/kicka).
- Klucze m.in.: `ban.screen` (ekran rozłączenia), `ban.deny` (próba wejścia na zbanowany tryb), `ban.broadcast` (ogłoszenie), analogicznie `mute.*`, `kick.*`, `warn.*`, plus `cmd.*` (feedback do admina, „brak uprawnień", „użycie").

Plik jest czytany jako properties (ISO-8859-1) — dla polskich znaków diakrytycznych użyj `\uXXXX` albo trzymaj się ASCII. Po edycji wystarczy restart serwerów (plik czytany przy starcie).

---

## 6. Uprawnienia: LuckPerms (zewnętrzny plugin)

Własnego systemu uprawnień **nie budujemy** — standardem jest **LuckPerms**:

- Wersje na **Paper** (shardy + limbo) i na **Velocity** (proxy) — komendy kar sprawdzają `hasPermission(...)` na proxy, więc LuckPerms-Velocity jest wymagany do nadawania uprawnień stafowi.
- **Wspólny backend** (ten sam MongoDB/MySQL dla wszystkich instancji LuckPerms + włączony `messaging-service`/`redis`) → grupy i rangi są **sieciowe**: nadajesz raz, obowiązują wszędzie. To spina się z naszym kontem global (`players`) — ranga gracza jest wspólna dla wszystkich trybów.
- Węzły uprawnień kar: `elcartel.ban`, `elcartel.mute`, `elcartel.kick`, `elcartel.warn`. (Ekonomia admin: `elcartel.eco.admin`.)
- **Bootstrap przed LuckPerms:** Velocity bez dostawcy uprawnień zwraca `hasPermission = false`, a OP z Papera nie przechodzi na proxy. Żeby móc karać zanim wgrasz LuckPerms, wpisz nicki/UUID do `ELCARTEL_ADMINS` (po przecinku) w `elcartel.properties` — komendy kar honorują tę listę. Konsola proxy ma dostęp zawsze. Po wdrożeniu LuckPerms listę można wyczyścić.

Instalacja i konfiguracja: `network/ADMIN.md` → „Uprawnienia (LuckPerms)”.

> Dlaczego nie własny system: LuckPerms jest dojrzały, ma kontekst per-serwer/per-świat, dziedziczenie grup, web-editor i sieciową synchronizację. Pisanie własnego dałoby gorszy efekt mniejszym nakładem sensu. Rdzeń tylko **czyta** uprawnienia przez Bukkit/Velocity API (`hasPermission`), więc LuckPerms jest podmienialny.

---

## 7. Mapa zmian w kodzie

- **core-common:** `Durations` (parsowanie `7d`/`2h`/`perm`, format „pozostało”).
- **core-data:** `Punishment` (kolekcja `punishments`) + `PunishmentRepository` (`add`, `active(uuid,type,scope)`, `effectiveBan`/`effectiveMute` = network ∥ tryb, `pardon`, indeksy). `Messages` (teksty z `messages.properties` + defaulty). `CoreData.punishments()`, `CoreData.messages()`.
- **core-velocity:** `PunishmentGuard` (`LoginEvent` ban sieciowy; `ServerPreConnect` ban trybu; teksty z `Messages`) + `PunishCommands` (12 komend: rodzina tryb + `proxy`, zakres z trybu nadawcy/`-t`, przełącznik `pokaz`/`cichy`, `hasPermission`/`ELCARTEL_ADMINS`, publikacja `core:punish` przy mute) + `LegacyText` (`&`→Adventure). Rejestracja w `CoreVelocityPlugin`.
- **core-paper:** `MuteService` (cache wyciszeń + scope + subskrypcja `core:punish`, komunikat z `mute.deny`) + `MuteListener` (czat/join/quit) + `LegacyText`. Wpięte w bloku sharda (`CorePaperPlugin`).
- **Uprawnienia:** zewnętrzny LuckPerms (Paper + Velocity), wspólny backend; rdzeń tylko `hasPermission`.

---

## Źródła / powiązane

- `BLUEPRINT.md` sek. 8 (model danych — `punishments`), `BLUEPRINT-modes.md` (dane per-tryb), `network/ADMIN.md` (operacje + LuckPerms), `network/TEST.md` (diagnostyka).
- [LuckPerms — dokumentacja](https://luckperms.net/wiki/Home)
