# El Cartel Network Core 

Witaj w głównej dokumentacji silnika **El Cartel Network**. Projekt ten składa się z zestawu autorskich pluginów współpracujących na poziomie proxy (Velocity) oraz serwerów końcowych (Paper), zintegrowanych za pomocą baz danych Redis i MongoDB.

## 🏗️ Architektura Projektu

Projekt został niedawno zrefaktoryzowany. Zamiast dawnego "miszmaszu", kod został podzielony na wyizolowane, logiczne paczki (moduły):

- **`core-common`**: Współdzielone struktury, stałe i narzędzia (`CoreConstants`, formatowanie czasu).
- **`core-data`**: Połączenie z bazami danych (MongoDB, Redis), modele danych, usługi pub/sub oraz API dla poszczególnych modułów.
- **`core-velocity`**: Główny rdzeń na proxy (Bungee/Velocity).
  - `auth/`: Bramka logowania, ochrona kont (Limbo).
  - `command/`: Komendy proxy (`/play`, `/channels`, `/tp`, kary).
  - `punish/`: Ochrona przed dołączaniem zbanowanych graczy.
  - `shard/`: Inteligentny routing między różnymi kanałami (sektorami).
- **`core-paper`**: Silnik na każdym z serwerów końcowych (Paper).
  - `auth/`: Moduły obsługujące zlecenia logowania/rejestracji (bramka).
  - `chat/`: Ograniczenia czatu i system globalnych kar (Mute).
  - `eco/`: Zabezpieczenia ekonomii, monitoring waluty i logowanie anomalii.
  - `profile/`: Trwały zapis i synchronizacja ekwipunków, enderchestów i zdrowia między sektorami.
  - `sector/`: Rozszerzenia dla hubów, komendy `/spawn`, menu serwerów.
  - `security/`: Ochrona przeciw kopiowaniu (AntiDupe), spamowaniu (AntiSpam) oraz próbom crashowania (CrashProtection).
- **Tryby gry (`mode-hub`, `mode-survival`, `mode-oceanblock`)**: Cienkie wtyczki ładujące się na specyficznych serwerach, konfigurujące powitania, scoreboardy, ekonomię i sklepy per-tryb.

---

## 🔒 Lista Zabezpieczeń

System posiada wbudowany pakiet ochronny zabezpieczający przed popularnymi wektorami ataków:

> [!TIP]
> Zmniejszono limit sprawdzania kliknięć w ekwipunku (`AntiDupeListener`) do **50 ms** na prośbę administracji, aby ułatwić i poprawić płynność normalnej gry ("shift-click"), przy zachowaniu bezpieczeństwa.

1. **Anti-Crash (Book/Sign)**: Chroni przed tzw. zrzutami pamięci i przeładowaniami czcionek, wywoływanymi złośliwymi pakietami edycji długich książek i znaków specjalnych Unicode.
2. **Anti-Lag**: Limituje ilość zwierząt, potworów i aktualizacji redstone na jeden chunk.
3. **Anti-Spam (Czat & Komendy)**: Blokuje zbyt szybkie wpisywanie wiadomości i komend na serwerach, dając serwerowi oddech.
4. **Economy Guard**: Monitoruje przelewy z wysokimi stawkami (ponad ustalone limity na minutę) zapobiegając wylewaniu pieniędzy (dupe) na rynek, zapisując operacje na Redis pub/sub do powiadomień dla administracji.
5. **Anti-Dupe**: Anuluje kliknięcia w ekwipunku oraz uniemożliwia upuszczanie przedmiotów podczas bycia "zamrożonym" (np. podczas logowania lub transferu między sektorami). W najnowszej poprawce wprowadzono synchronizację po anulowaniu (zapobieganie tzw. "znikającym przedmiotom ghost").

---

## 🔑 Rejestr Uprawnień (Permisje)

Zestaw wszystkich uprawnień wykorzystywanych przez autorskie oprogramowanie:

| Uprawnienie | Moduł | Opis | Wymagany Ranga |
| :--- | :--- | :--- | :--- |
| `elcartel.admin` | **Ogólny** | Podstawowe uprawnienie administratorskie dające dostęp m.in. do komend nakładania globalnych kar (`/ac-punish`) oraz powiadomień (Mute, EcoGuard). | `ADMIN` / `ROOT` |
| `elcartel.eco.admin` | **Ekonomia** | Zarządzanie centralną, sektorową ekonomią (`/eco take/give/set`). Pozwala na generowanie pieniędzy i zmienianie zasobów graczy. | `ROOT` |
| `elcartel.bypass.spam` | **Bezpieczeństwo** | Omija absolutnie wszystkie filtry Anti-Spam (zarówno wiadomości jak i komendy, a także limity kliknięć) oraz omija wyciszenia (Mute). | `ROOT` |
| `elcartel.admin.punish` | **Velocity** | Nadaje prawa do używania komend karzących bezpośrednio z poziomu proxy (`/ban`, `/kick`, `/mute` globalnie). | `MOD` / `ADMIN` |
| `elcartel.admin.tp` | **Velocity** | Prawo do teleportowania się pomiędzy serwerami i do graczy ukrytych w różnych hubach/sektorach (`/tp`). | `ADMIN` |

> [!NOTE]
> Jeżeli gracz ma przypisaną dziedziczoną gwiazdkę `*` (np. przez LuckPerms dla grupy ROOT), otrzyma on dostęp do wszystkich powyższych uprawnień automatycznie, ponieważ są one zaindeksowane jako domyślne dla operatorów.

---

## ⚙️ Centralna Konfiguracja Sieci

Zgodnie z wymaganiami technicznymi infrastruktury, wprowadzono koncepcję współdzielonych konfiguracji (Network Configs).
Wystarczy użyć symlinków (`mklink /D` na Windowsie lub `ln -s` na Linuxie) by wszystkie serwery czytały wspólną strukturę plików, bez konieczności duplikowania konfiguracji. Skrypt startowy w katalogu `network` może tym zarządzać z użyciem wspólnej ścieżki.
