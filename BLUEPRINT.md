# Blueprint sieci Minecraft 1000+ graczy

**Stack:** Paper 1.21.8 (backendy) · Velocity (proxy) · Java 21 + Gradle (core) · Hetzner (hosting hybrydowy) · Cloudflare (DNS) · TCPShield (anty-DDoS)
**Tryb auth:** premium + cracked (offline) · **Typ sieci:** mieszana (hub + tryby)
**Wersja dokumentu:** 1.0 · Data: 2026-06-01

> To jest referencja fundamentu. Każda sekcja to albo decyzja architektoniczna, albo runbook „krok po kroku". Kolejne etapy projektu (kod core'a) odwołują się do tych ustaleń.

---

## 1. Założenia i decyzje

| Obszar | Decyzja | Konsekwencja |
|---|---|---|
| Skala | 1000+ graczy jednocześnie | Sieć wielu instancji, nie pojedynczy serwer |
| Silnik | Paper 1.21.8 | Java 21, flagi Aikara |
| Proxy | Velocity (modern forwarding) | MAC chroni przed podszywaniem pod backend |
| Core | Java 21 + Gradle, multi-module | Minimum gotowych pluginów, pełna kontrola |
| Hosting | Hybryda Hetzner: dedyk AX + Cloud CCX | Mocny single-core pod grę, elastyczna reszta |
| Baza danych | MongoDB (replica set) | Model dokumentowy; trwałość profili, auth, kar, ekonomii |
| Auth | premium + cracked (offline-mode) | Najbardziej narażony tryb — auth wbudowany w core |
| DNS | Cloudflare (NS migrowane z OVH) | SRV + CNAME pod TCPShield |
| Anty-DDoS | TCPShield z przodu | IP Hetznera nigdy nieujawnione |
| Sklep | Własny: NestJS + Next.js | Spójny z siecią, dostawa przez MongoDB; zero prowizji platformy |
| Płatności | Bramka CashBill | BLIK/przelewy/karty/SMS u operatora; własnego przetwarzania kart nie robimy (PCI po stronie CashBill) |

**Krytyczna zasada bezpieczeństwa:** publicznie wystawione jest **wyłącznie proxy**, i to tylko za TCPShieldem. Wszystkie backendy, baza i Redis żyją w sieci prywatnej Hetznera i są nieosiągalne z internetu.

---

## 2. Architektura i topologia

```
                    Gracze
                      │
              [ Cloudflare DNS ]   (SRV → CNAME, proxy OFF)
                      │
              [   TCPShield   ]    (filtr L4/L7, ukrycie origin)
                      │  (tylko IP TCPShielda przepuszcza firewall)
        ┌─────────────┴─────────────┐
        │   VELOCITY (proxy)         │   Hetzner Cloud (CCX), publiczne IP
        │   modern forwarding + MAC  │
        └─────────────┬─────────────┘
                      │  sieć PRYWATNA (vSwitch + Cloud Network)
   ┌──────────┬───────┴───────┬────────────┬───────────────┐
   │  LIMBO   │   HUB ×2 (HA)  │  TRYB A ×N │  TRYB B ×N ... │  Paper 1.21.8 (dedyk AX)
   │ (auth/   │               │ (shardy)   │               │
   │ anti-bot)│               │            │               │
   └──────────┴───────────────┴────────────┴───────────────┘
                      │  sieć PRYWATNA
        ┌─────────────┴─────────────┐
        │  MongoDB   +   Redis       │   Hetzner Cloud (CCX) lub dedyk
        │ (trwałość)  (cache/pubsub) │
        └────────────────────────────┘
```

### Role komponentów

- **TCPShield** — reverse proxy TCP filtrujący ataki wolumetryczne i aplikacyjne; ukrywa realne IP. Backend (Velocity) widzi ruch tylko z jego sieci.
- **Velocity** — jedyny publiczny komponent. Trzyma sesje, przełącza graczy między backendami, modern forwarding podpisany sekretem (MAC). Skalowalne do 2+ instancji za wspólnym Redisem.
- **Limbo** — minimalny serwer, na który trafia każde nowe połączenie. Tu działa anti-bot i gateway auth; boty i nieautoryzowani nie obciążają huba.
- **Hub ×2** — lobby, wejście po autoryzacji. Dwie instancje dla HA i rozłożenia ruchu.
- **Serwery trybów (×N)** — shardowane wg obciążenia. „Mieszana" sieć = kilka różnych trybów równolegle.
- **MongoDB** — dane trwałe (profile, auth, kary, ekonomia); model dokumentowy, replica set dla HA/failover.
- **Redis** — cache, sesje, pub/sub między serwerami i proxy.
- **Sklep (NestJS + Next.js)** — osobny serwis web za Cloudflare WAF; zapisuje zakupy do MongoDB, a core realizuje dostawę w grze.
- **CashBill** — bramka płatności (BLIK, przelewy, karty, SMS/DirectBilling); przetwarzanie płatności i PCI po stronie operatora.

### Szacunki zasobów (start)

| Komponent | Instancje | RAM/instancję | Uwagi |
|---|---|---|---|
| Velocity | 1 (→2 HA) | 4 GB | ~512 MB / 500 graczy + ~1 GB zapasu; sieciowo zależny |
| Limbo | 1–2 | 1–2 GB | bardzo lekki, kluczowy dla anti-bot |
| Hub | 2 | 6–8 GB | mały świat, duża liczba graczy |
| Tryb gry | N (shardy) | 8–16 GB | zależnie od mechanik; single-core bound |
| MongoDB | 1 (→ replica set 3) | 8–16 GB | NVMe; replica set zalecany dla HA/failover |
| Redis | 1 | 2–4 GB | trzymać `maxmemory` + polityka eviction |
| Sklep (NestJS+Next.js) | 1 (→2) | 2–4 GB | za Cloudflare WAF; do MongoDB po sieci prywatnej |

### Skalowanie do 1000+

Skalujemy **poziomo**: dokładamy shardy trybów i kolejne huby, a nie zwiększamy graczy na jednej instancji (Paper realnie 150–500/instancję). Po przekroczeniu możliwości jednego proxy — drugi Velocity za wspólnym Redisem (RedisBungee / Velocity-CTD / MultiProxySync do synchronizacji listy graczy).

---

## 3. Infrastruktura Hetzner — runbook

### 3.1 Dobór maszyn

- **Backendy Paper → dedyk AX (linia Ryzen, wysokie taktowanie).** Logika gry jest single-core-bound; liczy się zegar i IPC. Model w stylu **AX102** (Ryzen 9, DDR5, 2× NVMe) jest pod to idealny. Sprawdź aktualną ofertę przed zamówieniem.
- **Proxy + DB + Redis → Hetzner Cloud (linia CCX, dedykowane vCPU).** Łatwa sieć prywatna, szybkie skalowanie góra/dół, stabilna wydajność (CCX = dedicated vCPU, nie współdzielone).
- **NVMe obowiązkowo** wszędzie. Włącz wbudowaną ochronę DDoS Hetznera (jest domyślnie na łączu) jako dodatkową warstwę.

### 3.2 Sieć prywatna: vSwitch + Cloud Network

Cel: spiąć dedyki (Robot) i serwery Cloud w jedną sieć L2/L3, żeby ruch między nimi nie szedł publicznie.

1. **Utwórz vSwitch** w panelu Robot, przypisz **VLAN ID z zakresu 4000–4091**.
2. Podłącz dedyki do vSwitcha; skonfiguruj interfejs VLAN na każdym (IP z podsieci vSwitcha, **MTU 1400**).
3. Utwórz **Cloud Network** i dodaj do niej **subnet typu vSwitch**, wskazując ten sam vSwitch — to łączy serwery Cloud z dedykami.
4. Sprawdź łączność: serwery Cloud muszą pingować się nawzajem po prywatnych IP, a dedyki — po IP z podsieci vSwitcha.
5. Wszystkie usługi (Paper, MongoDB, Redis) **bindujemy do prywatnych IP**, nigdy do publicznych.

> MTU 1400 jest istotne — większe ramki potrafią ginąć na vSwitchu i powodować „losowe" timeouty.

### 3.3 Hardening OS (Debian/Ubuntu, każdy serwer)

- Osobny użytkownik nie-root (`mc`), logowanie SSH **tylko po kluczu**, `PermitRootLogin no`, `PasswordAuthentication no`, niestandardowy port SSH.
- `unattended-upgrades` na poprawki bezpieczeństwa.
- **fail2ban** na SSH (i opcjonalnie na logi Velocity).
- Sysctl: wyłącz przekierowania ICMP redirect, włącz `tcp_syncookies`, ogranicz `somaxconn`/`tcp_max_syn_backlog` rozsądnie pod duży ruch.
- Czas: `chrony`/`systemd-timesyncd` (UUID/sesje wrażliwe na rozjazd zegara).
- Sekrety (forwarding.secret, hasła DB) poza repo — w `~/.config` z `chmod 600` lub menedżerze sekretów.

### 3.4 Firewall (nftables)

**Proxy (publiczne)** — przepuszcza Minecraft tylko z TCPShielda, SSH tylko z IP admina/VPN:

```nft
table inet filter {
  set tcpshield_v4 {
    type ipv4_addr; flags interval;
    # wypełniane z OFICJALNEJ listy IP TCPShielda (auto-skrypt, patrz 4.4)
  }
  chain input {
    type filter hook input priority 0; policy drop;
    ct state established,related accept
    iif "lo" accept
    ip saddr <ADMIN_IP_LUB_VPN> tcp dport <PORT_SSH> accept
    ip saddr @tcpshield_v4 tcp dport 25565 accept
    ip protocol icmp icmp type echo-request limit rate 5/second accept
  }
}
```

**Backendy / DB / Redis (prywatne)** — przyjmują ruch **tylko z prywatnych IP** proxy i pozostałych węzłów; z publicznego interfejsu polityka `drop`. Port Minecraft backendu nasłuchuje wyłącznie na IP prywatnym.

---

## 4. DNS, Cloudflare i anty-DDoS — runbook

### 4.1 Migracja NS OVH → Cloudflare

1. Dodaj domenę w Cloudflare, przepisz istniejące rekordy.
2. W panelu OVH zmień **nameservery** na te podane przez Cloudflare.
3. Poczekaj na propagację (do 24 h); zweryfikuj status „Active" w Cloudflare.

### 4.2 TCPShield: backend + sieć

1. W panelu TCPShield dodaj **Network** i **Backend Set** wskazujący na **publiczne IP proxy : 25565**.
2. Pobierz z panelu **chroniony CNAME** (np. `xxxx.tcpshield.net`).
3. Włącz wysyłanie **HAProxy / PROXY protocol** do backendu (potrzebne, by Velocity znał prawdziwe IP gracza — patrz 5.2).

### 4.3 Rekordy w Cloudflare

> Pułapki: port w SRV **musi** być `25565` (mapowanie portu robi TCPShield, nie DNS). SRV **nie** może celować bezpośrednio w chroniony CNAME — inaczej klient dostanie „Invalid host".

1. **CNAME**: `tcpshield` → `xxxx.tcpshield.net` — **proxy status OFF** (szara chmurka, „DNS only").
2. **SRV**: `_minecraft._tcp.play` → priorytet `0`, waga `0`, **port `25565`**, target `tcpshield.twojadomena.pl`.
3. Gracze łączą się przez `play.twojadomena.pl`.

Rekordy MC zawsze **DNS only** (Cloudflare nie proxuje TCP gry — od tego jest TCPShield).

### 4.4 Whitelist IP TCPShielda na firewallu proxy

To **najważniejszy** punkt — bez niego ktoś znajdzie origin IP i ominie TCPShield (a tym samym całą ochronę DDoS i część anti-bota).

- Pobieraj **oficjalną, aktualną listę IP TCPShielda** i wstrzykuj do setu `tcpshield_v4` (cron/skrypt aktualizujący, np. typu „Firewall-IPWhitelist").
- Wszystko inne na porcie 25565 → `drop`.
- **Test poprawności:** z zewnętrznej maszyny `nmap -p25565 <publiczne_IP_proxy>` powinno pokazać port jako filtrowany/zamknięty; połączenie powinno działać **wyłącznie** przez `play.twojadomena.pl`.

### 4.5 Domena sklepu (proxy ON — inaczej niż MC)

Sklep to ruch HTTP, więc korzysta z pełnej ochrony warstwy web Cloudflare — odwrotnie niż rekordy MC (DNS only).

- `sklep.twojadomena.pl` → rekord A/AAAA na IP serwera sklepu, **proxy Cloudflare ON** (pomarańczowa chmurka): WAF, rate limiting, ochrona DDoS L7.
- WAF z regułami OWASP, rate limiting na endpointy logowania/zakupu, Bot Fight Mode.
- TLS: tryb **Full (strict)** + origin certificate na serwerze sklepu.
- Endpoint notyfikacji CashBill (np. `/payment/cashbill/notify`) publiczny, ale akceptuje wyłącznie poprawnie podpisane żądania; opcjonalnie whitelist IP CashBilla.
- Backend sklepu łączy się z MongoDB po **sieci prywatnej**, nie publicznie.

---

## 5. Warstwa serwerowa (Paper / Velocity)

### 5.1 Wersje i Java

- Minecraft **1.21.8** → **Java 21** (baseline 1.21.x). Uruchamiamy na aktualnym LTS; zweryfikuj wymaganie konkretnego builda Paper przed wdrożeniem (pobieranie z PaperMC „Fill" API).
- Te same wersje Javy i Paper na wszystkich backendach.

### 5.2 Velocity (`velocity.toml`)

```toml
bind = "0.0.0.0:25565"
show-max-players = 1000
online-mode = false                       # cracked dozwolony; premium weryfikuje nasz core
force-key-authentication = false          # cracked/część klientów wymaga false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[advanced]
proxy-protocol = true                     # odbiór HAProxy/PROXY protocol od TCPShielda
```

- `forwarding.secret` — długi losowy sekret; **ten sam** wpisujemy na backendach.
- `proxy-protocol = true` sprawia, że bezpośrednie połączenia (bez nagłówka PROXY) odpadają — wzmacnia wymuszenie ruchu przez TCPShield.

### 5.3 Paper 1.21.8

`config/paper-global.yml` (na każdym backendzie):

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: false        # MUSI zgadzać się z online-mode w velocity.toml
    secret: "<TEN_SAM_SECRET_CO_W_forwarding.secret>"
```

`server.properties`:

```properties
online-mode=false
server-ip=<PRYWATNE_IP_BACKENDU>   # bind tylko do sieci prywatnej
prevent-proxy-connections=false
network-compression-threshold=256
```

**Flagi Aikara (Java 21, heap 12 GB+):**

```bash
java -Xms12G -Xmx12G \
 -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch \
 -XX:G1NewSizePercent=40 -XX:G1MaxNewSizePercent=50 -XX:G1HeapRegionSize=16M \
 -XX:G1ReservePercent=15 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \
 -XX:InitiatingHeapOccupancyPercent=20 -XX:G1MixedGCLiveThresholdPercent=90 \
 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 \
 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 \
 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true \
 -jar paper-1.21.8.jar --nogui
```

Trzymaj `Xms == Xmx`. Dla heapu ≤12 GB użyj wariantu bazowego (bez podbitych `G1NewSizePercent`).

### 5.4 Limbo, hub, tryby

- **Limbo** — minimalny świat, `view-distance=2`, `simulation-distance=2`, brak mobów/entity. Tu ląduje każdy nowy gracz.
- **Hub** — niski `view/simulation-distance`, dużo graczy, mało symulacji.
- **Tryby** — konfiguracja per typ; shardowanie przez routing w core-velocity.

---

## 6. Model bezpieczeństwa

Warstwowo, od krawędzi do aplikacji:

| Warstwa | Zagrożenie | Mitygacja |
|---|---|---|
| Sieć (L3/L4) | DDoS wolumetryczny | TCPShield + wbudowana ochrona Hetznera |
| Sieć (L7) | flood logowań, fake handshake | filtr TCPShield + anti-bot na limbo |
| Origin | wykrycie IP i bypass proxy | firewall: MC tylko z IP TCPShielda; backendy w sieci prywatnej |
| Forwarding | podszywanie się pod proxy | modern forwarding (MAC) + wspólny secret |
| Bezpośrednie wejście na backend | omijanie auth | backend bind do IP prywatnego, `proxy-protocol`, brak trasy z internetu |
| Aplikacja | boty, multikonta, spam | własny anti-bot, rate-limit, IP-binding, kolejka |
| Eksploity | crash-pakiety, malformed packets | sanity-check pakietów, limity, aktualny Paper |
| Konta | przejęcie konta cracked | hasła Argon2, 2FA opcjonalnie, IP-binding |
| Web sklepu (L7) | OWASP, boty, DDoS HTTP | Cloudflare WAF + rate limiting + Bot Fight |
| Webhook płatności | podrobiona notyfikacja | weryfikacja podpisu CashBill + idempotencja + sprawdzenie kwoty |
| Płatności | chargeback / fraud | cofanie dostawy, limity per konto/IP, weryfikacja nicku |
| Dane osobowe | wyciek / RODO | minimalizacja, szyfrowanie, dostęp wg ról |
| Wstrzyknięcia | NoSQLi / XSS / CSRF | walidacja wejścia, sanityzacja, tokeny CSRF |

**Test akceptacyjny bezpieczeństwa:** z zewnątrz nie da się połączyć z żadnym backendem ani z proxy z pominięciem TCPShielda; `nmap` na publiczne IP nie pokazuje otwartego 25565 poza ścieżką TCPShield.

---

## 7. Auth: premium + cracked

Najbardziej narażony tryb — Velocity działa w `online-mode=false`, więc **dopóki gracza nie zweryfikujemy, może podać dowolny nick**. Auth budujemy w core (`core-velocity` + `core-data`).

### Przepływ

```
połączenie → LIMBO (anti-bot, throttle per IP, weryfikacja handshake)
   │
   ├─ nick istnieje jako konto PREMIUM (lookup Mojang)?
   │      └─ TAK → wymuś weryfikację online (mechanizm jak FastLogin):
   │                 prawdziwe UUID Mojang, auto-login, przerzut na HUB
   │
   └─ NIE (cracked) → /register (pierwszy raz) lub /login
                         hasło Argon2, IP-binding, opcj. 2FA
                         po sukcesie → przerzut na HUB
```

### Zasady

- **Rezerwacja nicków premium:** jeśli lookup w Mojang zwraca istniejące konto, **wymuszamy login premium** — cracked nie podszyje się pod nick gracza premium.
- **UUID:** premium dostają prawdziwe (online) UUID, cracked — deterministyczne offline UUID. Spójne mapowanie trzymamy w DB, żeby dane gracza nie „skakały".
- **Anti-bot na limbo:** limit nowych połączeń per IP/sekundę, wykrywanie nienaturalnych wzorców handshake, throttling, opcjonalna whitelista/greylista.
- **Hasła:** Argon2id, nigdy plaintext; rate-limit prób logowania; lockout po serii nieudanych prób.
- **2FA (opcjonalne)** dla cracked i dla rang z uprawnieniami.

---

## 8. Architektura core (Gradle multi-module)

```
mc-core/
├── settings.gradle.kts
├── build.gradle.kts
├── core-common/     # API, modele, eventy, util — czysta Java, bez zależności do platform
├── core-data/       # MongoDB (oficjalny Java Driver + POJO codec) + Redis (Lettuce), repozytoria
├── core-paper/      # plugin Paper: profile, komendy, ekonomia, kary, hooki anti-cheat
└── core-velocity/   # plugin Velocity: routing, kolejka, anti-bot, gateway auth, sync graczy
```

**Zasada zależności:** `core-paper` i `core-velocity` zależą od `core-common` i `core-data`; `core-common` nie zależy od niczego platformowego (łatwe testy jednostkowe).

> Sklep (NestJS) to **osobny serwis**, nie moduł Gradle — integruje się przez współdzielone MongoDB i kanał Redis `core:purchase`; dostawę w grze realizuje konsument w `core-paper`.

### Zarys modelu danych (MongoDB — kolekcje)

- `players` — `_id` = uuid, name (indeks unique), premium (bool), firstSeen, lastSeen, lastIp
- `auth` — `_id` = uuid, passwordHash (argon2id), totpSecret, registeredIp
- `punishments` — `_id`, uuid (indeks), type (ban/mute/kick/warn), reason, by, expiresAt (TTL opcjonalnie)
- `economy` — `_id` = uuid, balance (lub osadzone w `players`)
- `permissions` / `groups` — model uprawnień
- (opcjonalnie) `homes`, `warps`, statystyki trybów — naturalnie jako pod-dokumenty profilu

> Schemaless: osadzamy dane tam, gdzie to sensowne (homes/statystyki w dokumencie gracza) zamiast joinów. Indeksy na `_id` (uuid) i `name` (unique). Brak migracji SQL — wersjonowanie schematu na poziomie aplikacji (opcjonalnie Mongock).

### Kanały Redis (pub/sub)

- `core:msg` — komunikacja między serwerami (broadcast, cross-server cmd)
- `core:player` — join/quit, sync stanu gracza
- `core:queue` — kolejki do trybów
- `core:proxy` — sync między proxy (przy 2+ instancjach)
- `core:purchase` — powiadomienia o opłaconych zakupach (sklep → dostawa w grze)
- `core:chat:<mode>` — w pełni asynchroniczny czat cross-server dla poszczególnych trybów (zintegrowany z LuckPerms)
- `elcartel:tp` (plugin message) oraz klucz `tp:dest:<uuid>` (Redis) — służą do globalnego teleportowania graczy między serwerami z automatycznym śledzeniem docelowej maszyny.

### Uprawnienia i Rangi (LuckPerms)
Zdecydowano się na użycie LuckPerms (zainstalowane zarówno na instancjach Paper jak i Velocity). Wszystkie instancje podpięte są centralnie pod wspólną bazę MongoDB, co gwarantuje natychmiastową synchronizację rang na poziomie całej sieci.

### Kolejność implementacji (kamienie milowe)

> Status na 2026-06-22. Projekty nowych etapów w dokumentach towarzyszących: [BLUEPRINT-auth.md](BLUEPRINT-auth.md) (M2) oraz [BLUEPRINT-kanaly.md](BLUEPRINT-kanaly.md) (M3 + rozszerzenie skali do setek tysięcy graczy: silnik Paper, cap 150–200/shard).

- **M0 ✅ zrobione** — szkielet Gradle + buildujące się pluginy „hello" na Paper 1.21.8 i Velocity (`mc-core/`).
- **M1 ✅ zrobione** — `core-data` (MongoDB + Redis): profil gracza, lock z lease, sygnał handoffu, rejestr sesji, pub/sub.
- **M2 ✅ zrobione** — auth: gateway na proxy (premium → online-mode, anti-bot per IP) + flow na limbo (`/register` `/login` `/otp`, Argon2id, 2FA TOTP, lockout). Szczegóły: `BLUEPRINT-auth.md`.
- **M3 ✅ zrobione** — rejestr/heartbeat shardów, routing + kanały (`/play`, `/channels`, autoscaling serwerów), handoff pełnego profilu (lock-lease + wersja, serializacja NBT). Szczegóły: `BLUEPRINT-kanaly.md`.
- **M4 ✅ zrobione** — ekonomia per-tryb (`BLUEPRINT-modes.md`), **kary** (network + per-tryb; egzekwowanie na proxy + mute na shardach — `BLUEPRINT-kary.md`), uprawnienia = LuckPerms (zewnętrzny), framework komend/configów.
- **M5 🔶 w toku** — **sektory survivala** (bariery, bezszwowy transfer handoff, BossBar, przesuwanie granic — `BLUEPRINT-sektory.md`), GUI wybieraka kanałów (`/ch`), monitoring, load-test, tuning TPS/GC.
- **M6** — integracja sklepu: konsument dostaw (`deliveries`), idempotencja, cofanie przy chargebacku.

---

## 9. Sklep internetowy (rangi, klucze, przedmioty)

Własny serwis: **frontend Next.js + backend NestJS**, płatności przez **CashBill**, dane w tym samym **MongoDB**. Sklep nie jest modułem Gradle — integruje się z core przez współdzieloną bazę i Redis.

### Przepływ zakupu i dostawy

```
Gracz → Next.js (sklep) → NestJS API
  │  wybór: ranga / klucz / przedmiot + podanie nicku
  ▼
NestJS tworzy płatność w CashBill → redirect gracza na stronę CashBill
  │  (BLIK / przelew / karta / SMS / DirectBilling)
  ▼
CashBill → NestJS: notyfikacja server-to-server (POST na adres potwierdzenia)
  │  ✓ weryfikacja podpisu `sign`
  │  ✓ zgodność `id` / `service` / `amount` z zamówieniem
  │  ✓ status płatności = opłacona
  │  ✓ idempotencja po ID transakcji
  ▼
NestJS zapisuje `deliveries` (status PENDING) i publikuje `core:purchase` (Redis)
  ▼
Core (konsument w core-paper / core-velocity):
  • gracz ONLINE  → dostawa natychmiast (ranga / klucz / itemy)
  • gracz OFFLINE → realizacja przy najbliższym wejściu
  • po sukcesie → `deliveries.status = DONE`
```

### Zasady integracji i bezpieczeństwa płatności

- **Nigdy nie ufaj przekierowaniu klienta** — stan zamówienia ustala wyłącznie notyfikacja server-to-server od CashBill (z weryfikacją podpisu). Powrót gracza do sklepu to tylko UX.
- **Weryfikacja podpisu `sign`** każdej notyfikacji; odrzucanie niepodpisanych/niezgodnych.
- **Weryfikacja kwoty i waluty** po stronie serwera względem cennika — cenę ustala backend, nie klient.
- **Idempotencja** — notyfikacja może przyjść wielokrotnie; dostawa wykonywana dokładnie raz (flaga w `deliveries`).
- **Chargeback / zwrot** — obsługa statusu zwrotu → cofnięcie rangi/kluczy (kompensacja przez core).
- **PCI-DSS** — kart nie dotykamy; pełne przetwarzanie po stronie CashBill (hosted/redirect).
- **Anty-fraud** — limity zakupów per konto/IP, weryfikacja istnienia nicku w sieci, opóźniona dostawa przy podejrzanych wzorcach.

### Model danych (dodatkowe kolekcje MongoDB)

- `products` — `_id`, typ (`rank`/`key`/`item`), nazwa, cena, payload (grupa uprawnień / ilość kluczy / lista itemów), aktywny
- `orders` — `_id`, nick/uuid, pozycje, kwota, status (`NEW`/`PAID`/`REFUNDED`), `cashbillTxnId`, createdAt
- `deliveries` — `_id`, orderId, uuid, payload, status (`PENDING`/`DONE`/`REVOKED`), attempts, deliveredAt

### Zgodność prawna (PL/EU)

- **Regulamin sklepu** i polityka prywatności; **RODO** — minimalizacja danych, podstawa przetwarzania, prawo dostępu/usunięcia.
- **Faktury/paragony** zgodnie z przepisami; przy sprzedaży konsumenckiej — obowiązki informacyjne.
- **Prawo odstąpienia**: dla treści cyfrowych dostarczanych natychmiast zbierz zgodę na rozpoczęcie i poinformuj o utracie prawa odstąpienia.
- Sprzedaż rang/kluczy/przedmiotów jako treści cyfrowych — jasno opisana w regulaminie.

> To nie jest porada prawna. Przed startem skonsultuj regulamin, RODO i kwestie podatkowe ze specjalistą.

---

## 10. Monitoring i operacje

- **Metryki:** Prometheus + `node_exporter` (host) + eksporter Paper/`spark`; dashboardy Grafana (TPS, MSPT, RAM/GC, gracze, ruch sieciowy).
- **Alerty:** spadek TPS, wzrost MSPT, RAM blisko limitu, nietypowy ruch na proxy/limbo (sygnał ataku).
- **Backupy:** MongoDB (`mongodump` lub snapshoty z sekundarnej repliki + retencja), snapshoty światów; test odtworzenia.
- **Logi:** centralizacja (np. Loki) — szybkie korelowanie ataków i błędów.
- **Płatności/dostawa:** alert na zaległe dostawy (`deliveries` w `PENDING` zbyt długo), błędy webhooków, reconciliation z CashBill.

---

## 11. Checklist wdrożenia (pre-launch)

1. Dedyki AX i serwery Cloud zamówione, NVMe, OS zahardenowany.
2. vSwitch + Cloud Network działają; węzły pingują się po prywatnych IP (MTU 1400).
3. Firewall: backendy/DB/Redis nieosiągalne z internetu (zweryfikowane `nmap` z zewnątrz).
4. NS przeniesione na Cloudflare, status „Active".
5. TCPShield: backend set + HAProxy protocol; CNAME (proxy OFF) + SRV (port 25565) ustawione; `play.domena` łączy.
6. Whitelist IP TCPShielda aktywny i auto-aktualizowany; 25565 na proxy filtrowany dla reszty świata.
7. Velocity: modern forwarding + secret; `proxy-protocol = true`; bind OK.
8. Paper: `paper-global.yml` forwarding zgodny (secret + online-mode=false); bind do IP prywatnego.
9. Auth: premium auto-detekcja + rezerwacja nicków + register/login działają; anti-bot przetestowany.
10. Monitoring + alerty + backupy działają.
11. Load-test: symulacja 1000+ połączeń; TPS i GC stabilne.
12. Sklep za Cloudflare WAF (proxy ON), TLS Full (strict), nagłówki bezpieczeństwa.
13. CashBill: weryfikacja podpisu (`sign`) i kwoty działa; test płatności end-to-end z dostawą w grze.
14. Idempotencja dostawy przetestowana (podwójny webhook = jedna dostawa); chargeback cofa rangę/klucze.
15. Regulamin + polityka prywatności (RODO) opublikowane; zgoda na treści cyfrowe w procesie zakupu.

---

## Źródła

- [Paper — PaperMC](https://papermc.io/downloads/paper) · [Getting started / Java](https://docs.papermc.io/misc/java-install/)
- [Configuring player information forwarding — PaperMC Docs](https://docs.papermc.io/velocity/player-information-forwarding/) · [Configuring Velocity](https://docs.papermc.io/velocity/configuration/)
- [Aikar's flags — PaperMC Docs](https://docs.papermc.io/paper/aikars-flags/)
- [TCPShield — DNS Setup](https://docs.tcpshield.com/panel/dns-setup) · [Panel Configuration](https://docs.tcpshield.com/panel/panel-configuration) · [Setup Checklist](https://docs.tcpshield.com/troubleshooting/setup-checklist)
- [Hetzner — Connect Dedicated Servers (vSwitch)](https://docs.hetzner.com/networking/networks/connect-dedi-vswitch/) · [vSwitch](https://docs.hetzner.com/robot/dedicated-server/network/vswitch/)
- [Hetzner — gaming/dedicated](https://www.hetzner.com/4gamers/)
- [MongoDB — Java Driver](https://www.mongodb.com/docs/drivers/java/sync/current/) · [Replica Set](https://www.mongodb.com/docs/manual/replication/)
- [CashBill — dla deweloperów / API](https://www.cashbill.pl/deweloper/) · [Pobierz API](https://www.cashbill.pl/pobierz/api/)
- [Cloudflare WAF — dokumentacja](https://developers.cloudflare.com/waf/)
