# El Cartel — sieć (proxy + shardy)

Uruchamialna sieć: **Velocity** (proxy) + **limbo** (gateway auth) + **shardy** trybów (hub, survival, oceanblock…), spięte modern forwarding. Hub (lobby) jest trybem shardowanym jak każdy inny.

> **Jak wystartować: [START.md](START.md)** · **Obsługa na Linuksie: [ADMIN.md](ADMIN.md)**. Pełna konfiguracja ENV: `../mc-core/README.md`.

## Struktura

```
network/
├── setup.*          # pobranie jarów Paper/Velocity (Fill API v3)
├── preflight.*      # kontrola środowiska (Java/RAM/porty/sekret)
├── new-shard.*      # tworzenie shardów trybu z szablonu
├── save-template.*  # back-sync plugins/ z sharda do templates/<tryb>/
├── configure-luckperms.*  # wspólny LuckPerms (Mongo + Redis) na wszystkich serwerach
├── deploy-core.*    # wgranie jarów core do velocity + backendów
├── start-all.*      # start całości (limbo → shardy → velocity)
├── start-panel.*    # panel web w przeglądarce (monitoring + shardy)
├── panel/           # FastAPI + UI (Redis/Mongo, status procesów)
├── mc_ping.py       # smoke-test proxy (bez klienta)
├── elcartel.properties   # sekrety (Mongo/Redis) — gitignored
├── velocity/        # proxy (25565)
├── servers/limbo/   # gateway auth (jedyny stały backend)
└── templates/       # _base + szablony trybów (hub, survival, oceanblock)
```

## Skrypty

Windows: `powershell -ExecutionPolicy Bypass -File <skrypt>` albo `.\<skrypt>` (patrz START.md). Linux: `bash <skrypt>`.

- **`setup`** — pobiera Paper 1.21.8 + Velocity (jary do `servers/*` i `velocity/`).
- **`preflight`** — Java 21, RAM, wolne porty, zgodność forwarding-secretu.
- **`new-shard -Mode <tryb> -Count N`** — tworzy shardy trybu (wolny port z pasma, ENV, `core-paper.jar`). Szczegóły: `templates/README.md`.
- **`save-template <tryb>-<N>`** — kopiuje konfigi pluginów z działającego sharda z powrotem do `templates/<tryb>/` (nowe shardy dostaną te same ustawienia). Szczegóły: `templates/README.md`.
- **`deploy-core`** — kopiuje świeże `core-*.jar` po `gradle build`.
- **`start-all`** — startuje limbo → shardy → velocity.
- **`start-panel`** — panel web (`http://127.0.0.1:8080`): shardy z Redisa, status procesów, sesje, logi, tworzenie/usuwanie shardów. Szczegóły: [ADMIN.md](ADMIN.md) sekcja „Panel web”.
- **`mc_ping.py`** — pinguje proxy, wypisuje MOTD (diagnostyka: `TEST.md`).

## Produkcja / bezpieczeństwo (Hetzner)

- **Zmień `forwarding.secret`** na własny (`openssl rand -hex 24`) — ten sam w `velocity/forwarding.secret` i `proxies.velocity.secret` (`templates/_base/config/paper-global.yml`).
- Backendy binduj do **prywatnego IP** (vSwitch), nie `127.0.0.1`.
- Za TCPShieldem: `proxy-protocol = true` w `velocity.toml` + HAProxy protocol w panelu; firewall: MC tylko z IP TCPShielda. Szczegóły: `../BLUEPRINT.md` sek. 3–4.

`eula.txt=true` = akceptacja [EULA Minecraft](https://aka.ms/MinecraftEULA).
