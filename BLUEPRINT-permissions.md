# BLUEPRINT: Lista Permisji (Uprawnień)

Ten dokument zawiera oficjalny wykaz wszystkich uprawnień (permissions), które zostały zaprogramowane we flagowych pluginach jądra sieci (`core-paper` oraz `core-velocity`). Uprawnienia te należy przydzielić administracji za pomocą systemu takiego jak LuckPerms.

---

## 🛡️ Administracja Siecią i Kary (core-velocity)

Moduł kar i teleportacji operujący na warstwie proxy Velocity.

| Permisja | Opis |
| --- | --- |
| `elcartel.ban` | Pozwala na permanentne i tymczasowe banowanie graczy na całą sieć oraz ich odbanowywanie (`/ban`, `/unban`). |
| `elcartel.mute` | Pozwala na wyciszanie graczy oraz zdejmowanie wyciszeń we wszystkich trybach gry (`/mute`, `/unmute`). |
| `elcartel.kick` | Pozwala na wyrzucenie gracza z sieci proxy (`/kick`). |
| `elcartel.warn` | Upoważnia do nadawania graczom oficjalnych ostrzeżeń (`/warn`). |
| `elcartel.tp` | Zezwala na globalną teleportację między serwerami i trybami do innych graczy (`/tp`). |
| `elcartel.staff` | Bazowe uprawnienie administracyjne; stanowi warunek wstępny (fallback) do przeglądania ukrytych logów oraz dostępu do ogólnych komend moderatorskich. |

---

## 💰 Zarządzanie Ekonomią (core-paper)

Systemy wdrożone po stronie trybów gry.

| Permisja | Opis |
| --- | --- |
| `elcartel.eco.admin` | Pełny dostęp do zarządzania kontami graczy. Odblokowuje komendy wpłaty, wypłaty i nadpisania salda za pomocą `/eco give/take/set`. |
| `elcartel.admin` | Główne uprawnienie administracyjne. Właściciele tego uprawnienia m.in. **odbierają głośne alerty z Economy Guard** w przypadku wykrycia błędnego (nienaturalnego) zarobku. |

---

## 🚫 Bezpieczeństwo i Filtry (core-paper)

Filtry dbające o płynność i brak eksploitów w grze.

| Permisja | Opis |
| --- | --- |
| `elcartel.bypass.spam` | Gwarantuje niewidzialność dla zabezpieczeń Anti-Spam i Anti-Crash. Gracz posiadający tę permisję nie jest wyrzucany za spamowanie komendami, zbyt szybkie klikanie GUI czy posiadanie "nielegalnych" NBT w książkach i na tabliczkach. Wymagane dla właścicieli i developerów testujących exploity. |

---

*Ten dokument powstał automatycznie na bazie istniejącego kodu źródłowego.*
