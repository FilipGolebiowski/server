# El Cartel — diagnostyka i smoke-test sieci

Procedura **startu** jest w **[START.md](START.md)**. Tu: szybkie sprawdzenie, że proxy i backendy wstają i są poprawnie spięte modern forwardingiem.

## Preflight (środowisko)

Z `network/`: `powershell -ExecutionPolicy Bypass -File preflight.ps1` (Win) / `bash preflight.sh` (Linux). Sprawdza Java 21+, RAM, wolne porty, obecność jarów, zgodność forwarding-secretu. Idź dalej, gdy nie ma `[FAIL]` (poza brakiem jarów — to naprawia `setup`).

## Smoke-test proxy (bez klienta)

Gdy Velocity działa: `python mc_ping.py` → powinno wypisać MOTD `El Cartel - Network`, wersję i licznik graczy. Potwierdza, że proxy nasłuchuje (bez klienta Minecraft).

## Co oznacza sukces

- Każdy backend kończy log `Done (X.XXXs)!` bez błędów.
- Velocity: `Listening on /0.0.0.0:25565`.
- Klient 1.21.8 na `localhost` → **limbo** (auth) → po zalogowaniu **hub**. Dowód, że forwarding działa end-to-end.

## Troubleshooting

| Objaw | Przyczyna | Co zrobić |
|---|---|---|
| Kick „Unable to connect... forwarding" | sekret backendu ≠ proxy | `preflight` pokaże rozjazd; ujednolić `forwarding.secret` ↔ `proxies.velocity.secret` |
| `UnsupportedClassVersionError` | Java < 21 | wskaż JDK 21 |
| „Brak paper.jar / velocity.jar" | jary niepobrane | uruchom `setup.*` |
| `BindException` / port zajęty | inny proces na porcie | zamknij go; `preflight` wskaże port |
| Po zalogowaniu „Brak dostępnego huba" | brak shardu trybu hub | `new-shard -Mode hub -Count 1` |
| `/play <tryb>` mówi „brak kanałów" | brak shardu trybu / złe ENV | sprawdź `ELCARTEL_SHARD_ID`/`MODE`; `/channels <tryb>` |
| Panel: Redis/Mongo OFF | brak URI w `elcartel.properties` | uzupełnij jak dla backendów; restart panelu |
| Panel: 401 przy tworzeniu sharda | ustawiony `ELCARTEL_PANEL_TOKEN` | podaj token w formularzu (zapisany w localStorage) |
| Premium każe się rejestrować | rozjazd wykrywania premium | patrz `../BLUEPRINT-auth.md` sek. 7 (rezerwacja w bazie) |
| „Invalid host" przy SRV (prod) | port SRV ≠ 25565 | SRV zawsze 25565 (mapuje TCPShield), `../BLUEPRINT.md` 4.3 |
| `/ban` mówi „Brak uprawnień" | brak perm na **Velocity** / rozdzielone H2 | `configure-luckperms.py` + restart; `lp user <ty> parent add admin`; `../BLUEPRINT-kary.md` sek. 5 |
| Ban trybu nie blokuje wejścia | shard nazwany nietypowo (nie `<tryb>-N`) | tryb wyprowadzany z nazwy serwera; trzymaj nazewnictwo `survival-1`; `../BLUEPRINT-kary.md` sek. 3 |
| Mute nie działa / działa z opóźnieniem | brak propagacji `core:punish` (Redis) | sprawdź Redis URI w `elcartel.properties`; mute ładuje się przy wejściu, live-update przez `core:punish` |

## Uwaga

Sandbox asystenta nie ma Javy 21 ani RAM na komplet — `preflight` + `mc_ping.py` to powtarzalne sprawdzenie u Ciebie. Produkcja: `../BLUEPRINT.md` sek. 3–5.
