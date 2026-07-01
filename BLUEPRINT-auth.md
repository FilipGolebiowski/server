# Blueprint: bezpieczeństwo i autoryzacja (M2)

**Rozszerza:** `BLUEPRINT.md` (sekcje 6–7) o szczegóły implementacyjne.
**Zasada nadrzędna:** „nic nie wycieka" — serwer w trybie offline (premium + cracked) jest najbardziej narażonym punktem, więc auth, hasła i sekrety traktujemy z najwyższą ostrożnością.
**Wersja:** 1.0 · Data: 2026-06-22

> M2 dzielimy na dwie części: **A — bezpieczna podstawa** (kod w `core-data`, ten dokument, gotowe) i **B — integracja** (gateway w `core-velocity` + flow rejestracji/logowania na limbo). Ten dokument opisuje cały model; część B jest do dokończenia.

---

## 1. Model zagrożeń i założenia

| Zagrożenie | Mitygacja |
|---|---|
| Wyciek bazy → łamanie haseł | Argon2id (pamięciożerny), losowa sól per hasło, brak haseł jawnych |
| Wyciek sekretów 2FA | TOTP szyfrowany AES-256-GCM; klucz tylko w ENV, nie w bazie ani repo |
| Podszycie pod gracza premium (offline-mode) | wymuszenie weryfikacji online dla nicków premium (jak FastLogin) + rezerwacja nicków |
| Brute-force logowania | rate-limit + lockout (Redis), Argon2 spowalnia próby |
| Boty / flood połączeń | anti-bot na limbo: limit nowych połączeń per IP, throttling |
| Przejęcie konta cracked | IP-binding, opcjonalne 2FA, lockout |
| Wyciek przez logi | twarda zasada: NIGDY nie logujemy haseł, sekretów, kodów TOTP, tokenów |
| Wyciek origin/bypass | backendy w sieci prywatnej, tylko proxy publiczne (BLUEPRINT 3–4) |

Twarde zasady:

- **W offline-mode nie ufamy nickowi** dopóki gracza nie zweryfikujemy. Każde wejście ląduje najpierw na **limbo**.
- **Backendy, baza i Redis nieosiągalne z internetu** — wyłącznie przez sieć prywatną.
- **Minimalizacja danych** (RODO): trzymamy tylko to, co konieczne; dane wrażliwe oddzielone w kolekcji `auth`.

---

## 2. Przepływ autoryzacji

```
połączenie → VELOCITY (anti-bot: rate-limit per IP)
   │  nick == konto PREMIUM (lookup Mojang)?
   ├─ TAK → wymuś online-mode (PreLogin force online) → prawdziwe UUID Mojang
   │         → LIMBO → auto-login → HUB
   └─ NIE (cracked) → LIMBO (zamrożenie gracza)
                         │ pierwszy raz → /register <hasło> <hasło>
                         │ kolejny raz  → /login <hasło>  (+ /2fa <kod> jeśli włączone)
                         │   hasło: Argon2id; IP-binding; lockout po próbach
                         └─ sukces → HUB
```

Limbo trzyma gracza „zamrożonego" (brak ruchu/interakcji, timeout-kick), żeby boty i niezalogowani nie obciążali sieci ani nie wykonywali akcji.

---

## 3. Hasła — Argon2id

- **Algorytm:** Argon2id (Bouncy Castle, pure-Java, low-level API — bez rejestracji providera JCE).
- **Parametry (domyślne, mocne):** `m = 65536 KiB (64 MiB)`, `t = 3`, `p = 1`, hash 32 B, sól losowa 16 B. Profil OWASP-mocny (~250–400 ms/hash). Przy bardzo dużym ruchu logowań można zejść do OWASP-minimum `m=19456, t=2, p=1` (równoważna obrona, mniej RAM/CPU).
- **Sól per hasło** (losowa, `SecureRandom`); parametry zapisywane razem z hashem → możliwy bezpieczny **re-hash przy logowaniu**, gdy podniesiemy koszt (`needsRehash`).
- **Porównanie stałoczasowe** (`MessageDigest.isEqual`) — brak wycieku timing.
- **Hasło jako `char[]`, nie `String`** — nie zostaje w puli stringów; bajty czyszczone (`Arrays.fill`) zaraz po użyciu.
- **Nigdy** nie logujemy ani nie zwracamy hasła/hasha.

Kod: `core-data` → `auth/PasswordHasher`, `auth/PasswordHash`.

---

## 4. 2FA — TOTP (RFC 6238)

- Własna implementacja (HmacSHA1, krok 30 s, 6 cyfr) — bez zewnętrznej zależności; sekret w base32 (zgodny z Google/Microsoft Authenticator). Zweryfikowana oficjalnymi wektorami RFC 6238.
- **Sekret TOTP przechowywany WYŁĄCZNIE zaszyfrowany** (AES-256-GCM) w `auth.totpSecretEnc`. Nigdy jawnie.
- Weryfikacja z tolerancją ±1 krok (zegar), porównanie kodu stałoczasowe.
- 2FA zalecane dla rang z uprawnieniami i opcjonalne dla cracked.

Kod: `auth/TotpService`, `auth/SecretCipher`.

---

## 5. Zarządzanie kluczami

- **Klucz AES (32 B) i klucz HMAC tokenów** pochodzą ze **zmiennych środowiskowych / menedżera sekretów**, nigdy z repo ani bazy.
- `SecretCipher.fromBase64(System.getenv("ELCARTEL_TOTP_KEY"))` — przykład; klucz generowany raz, trzymany poza kodem.
- Rotacja: przy zmianie klucza re-szyfrujemy sekrety (migracja jednorazowa); tokeny sesji mają krótką ważność, więc rotacja klucza HMAC jest tania.
- Pliki sekretów (`forwarding.secret`, hasła DB) — `chmod 600`, poza repo (jak BLUEPRINT 3.3).

---

## 6. Anti-bot, rate-limit, lockout

- **Anti-bot na limbo / PreLogin:** `RateLimiter.allow("conn", ip, limit, window)` — limit nowych połączeń per IP w oknie; nadmiar odrzucany zanim obciąży sieć.
- **Lockout logowania:** `LoginThrottle` — licznik nieudanych prób per IP/nick; po progu blokada na `lockoutSeconds` (Redis). Reset po sukcesie.
- Argon2 z natury spowalnia brute-force; rate-limit + lockout domykają warstwę.
- Opcjonalnie greylista IP (pierwsze wejście z nowego IP pod większym rygorem).

Kod: `redis/RateLimiter`, `redis/LoginThrottle`.

---

## 7. Premium vs cracked

- **Rezerwacja nicków premium:** jeśli lookup w Mojang zwraca istniejące konto, **wymuszamy login premium** (online-mode dla tego połączenia) — cracked nie podszyje się pod nick gracza premium.
- **Trwałe zaklepanie konta (DB):** przy **pierwszym** wejściu gracza premium zapisujemy `PlayerAccount.premium = true` (rezerwacja nicku w bazie, po `nameLower`). Od tej pory:
  - PreLogin wymusza online-mode także **na podstawie bazy** (`isPremiumName`), niezależnie od dostępności API Mojanga — zamyka dziurę „Mojang down → cracked rejestruje premium-nick",
  - rejestracja cracked (`/register`) na zarezerwowany nick jest **blokowana** na limbo.
- **UUID:** premium → prawdziwe online-UUID (Mojang); cracked → **deterministyczne offline-UUID** (`OfflineUuid.of(name)`). Spójne mapowanie trzymane w profilu, żeby dane gracza „nie skakały".
- Wymuszenie online realizujemy w `core-velocity` na zdarzeniu PreLogin (część B).

Kod: `auth/OfflineUuid` (+ część B: gateway w `core-velocity`).

---

## 8. Tokeny sesji (multi-proxy)

- `SessionToken` — **HMAC-SHA256** nad `uuid:exp`, krótka ważność, weryfikacja stałoczasowa.
- Zastosowanie: bezpieczny transfer gracza **między proxy** (cookie ≤5 KiB, BLUEPRINT-kanaly sekcja 8) — token niesie **tylko** uuid + czas ważności (zero danych wrażliwych), podpisany kluczem z ENV. Docelowe proxy ufa sesji po weryfikacji podpisu.

Kod: `auth/SessionToken`.

---

## 9. Czego NIE logujemy i NIE przechowujemy (RODO)

- **Nigdy** w logach: hasła, hashe, sole, sekrety/kody TOTP, tokeny, pełne IP w nadmiarze. (Klasy `auth/*` celowo nie mają żadnego logowania — zweryfikowane.)
- Dane wrażliwe (`auth`) oddzielone od profilu; dostęp wg ról.
- **Prawo do usunięcia:** `AuthRepository.delete(uuid)` + usunięcie profilu — twarde skasowanie danych gracza.
- Minimalizacja: trzymamy `registeredIp`/`lastLoginIp` tylko do IP-bindingu/audytu; bez zbędnych danych osobowych.

---

## 10. Mapowanie na kod

**Część A — gotowe (`core-data`):**

| Element | Klasa |
|---|---|
| Rekord auth (Mongo) | `model/AuthRecord`, `repo/AuthRepository` |
| Hasła Argon2id | `auth/PasswordHasher`, `auth/PasswordHash` |
| Szyfrowanie sekretów (AES-GCM) | `auth/SecretCipher` |
| 2FA TOTP (RFC 6238) | `auth/TotpService` |
| Offline UUID | `auth/OfflineUuid` |
| Token sesji (HMAC) | `auth/SessionToken` |
| Anti-bot / lockout | `redis/RateLimiter`, `redis/LoginThrottle` |

**Część B — gotowe:**

- `core-velocity`: `AuthGateway` (PreLogin async — rate-limit per IP + premium → `forceOnlineMode`; ChooseInitialServer → limbo), `MojangClient` (lookup premium + cache Redis), routing na hub po `core:auth`.
- `core-paper` (limbo, gdy `ELCARTEL_ROLE=limbo`): `AuthGate` (zamrożenie gracza, `/register` `/login` `/otp`, Argon2 **asynchronicznie**, lockout, publikacja `core:auth`), `AuthListener`, `AuthCommands`.

**Konfiguracja (ENV):** `ELCARTEL_MONGO_URI`, `ELCARTEL_REDIS_URI`, `ELCARTEL_MONGO_DB` (=elcartel), `ELCARTEL_TOTP_KEY` (base64 32 B — 2FA); proxy dodatkowo `ELCARTEL_LIMBO`/`ELCARTEL_HUB`; limbo `ELCARTEL_ROLE=limbo`.

---

## Źródła

- [OWASP — Password Storage Cheat Sheet (Argon2id)](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [Bouncy Castle — bcprov-jdk18on (Argon2BytesGenerator)](https://www.bouncycastle.org/download/bouncy-castle-java/)
- [RFC 6238 — TOTP](https://datatracker.ietf.org/doc/html/rfc6238) · [RFC 4226 — HOTP](https://datatracker.ietf.org/doc/html/rfc4226)
- [Velocity — API / events (premium force, PreLogin)](https://docs.papermc.io/velocity/dev/api-basics/)
