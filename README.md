# El Cartel — sieć Minecraft (dokumentacja projektu)

Sieć Minecraft dużej skali: **Paper 1.21.11** (backendy) + **Velocity** (proxy), premium + cracked, własny rdzeń w Javie 21. Wiele trybów (każdy shardowany), pełny handoff gracza między shardami, docelowo multi-proxy i setki tysięcy graczy.

## Status

| Etap | Stan | Zakres |
|---|---|---|
| M0 | ✅ | szkielet Gradle + pluginy „hello" |
| M1 | ✅ | `core-data`: Mongo + Redis, profile, lock, handoff, sesje, pub/sub |
| M2 | ✅ | auth: gateway (premium/anti-bot) + limbo (`/register` `/login` `/otp`, Argon2id, 2FA, rezerwacja nicku) |
| M3 | ✅ | sharding trybów, routing (`/play`, `/channels`), handoff per-tryb, hub shardowany |
| M4 | ✅ | **ekonomia per-tryb** (`/balance` `/pay` `/eco`) · **kary network + per-tryb** (`/ban` `/mute` `/kick` `/warn`, `-t <tryb>`) · uprawnienia = **LuckPerms** |
| M5 | 🔶 w toku | **Sektory survivala** (bezszwowe przechodzenie, bariery, BossBar, transfer handoff) · GUI `/ch` |
| M6 | — | monitoring/load-test · sklep |

## Dokumenty

**Projekt (architektura):**

- [BLUEPRINT.md](BLUEPRINT.md) — fundament: infra Hetzner, DNS/TCPShield, bezpieczeństwo, kamienie.
- [BLUEPRINT-kanaly.md](BLUEPRINT-kanaly.md) — sharding kanałów + handoff + multi-proxy.
- [BLUEPRINT-auth.md](BLUEPRINT-auth.md) — auth/bezpieczeństwo (Argon2id, 2FA, rezerwacja premium).
- [BLUEPRINT-modes.md](BLUEPRINT-modes.md) — wiele trybów: dane global vs per-tryb, ekonomie per-tryb.
- [BLUEPRINT-kary.md](BLUEPRINT-kary.md) — kary (network + per-tryb), egzekwowanie, uprawnienia (LuckPerms).
- [BLUEPRINT-sektory.md](BLUEPRINT-sektory.md) — sektory survivala (świat dzielony na podserwery, bariera→transfer) + GUI `/ch`.

**Praktyka (jak uruchomić):**

- [network/START.md](network/START.md) — **jak wystartować** (build → deploy → shardy → start).
- [mc-core/README.md](mc-core/README.md) — rdzeń: build, moduły, **pełna konfiguracja ENV**.
- [network/README.md](network/README.md) — struktura sieci i skrypty · [network/TEST.md](network/TEST.md) — diagnostyka.
- [network/ADMIN.md](network/ADMIN.md) — **obsługa serwera na Linuksie** (tmux/systemd, logi, backup, restart, monitoring).
- [network/templates/README.md](network/templates/README.md) — jak dodać tryb.

## Repozytorium

```
elcartelgg/
├── BLUEPRINT*.md            # projekt (fundament, kanaly, auth, modes)
├── mc-core/                 # rdzeń Java 21 (core-common/data/paper/velocity)
└── network/                 # uruchamialna sieć
    ├── velocity/  servers/limbo/  templates/{_base,hub,survival,oceanblock}/
    └── setup.* preflight.* new-shard.* deploy-core.* start-all.* START.md
```

## Szybki start

1. **Build:** w `mc-core/` → `gradle build`.
2. **Jary + core:** w `network/` → `setup` → `deploy-core`.
3. **Shardy:** `new-shard -Mode hub -Count 1`, `... -Mode survival -Count 2`.
4. **Start:** `start-all`. Wymagania (Mongo/Redis) i szczegóły → **[START.md](network/START.md)**.

Sekrety (Mongo/Redis) w `network/elcartel.properties`; pełne ENV w `mc-core/README.md`.
