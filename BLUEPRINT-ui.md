# Blueprint: Interfejs Gracza (Scoreboard, Tablist, Nametags)

**Rozszerza:** `BLUEPRINT.md`
**Wersja:** 1.0 · Data: 2026-06-25

Dokument ten opisuje scentralizowaną architekturę zarządzania interfejsem gracza (UI) w rdzeniu sieci (`core-paper`), która obejmuje wydajne scoreboardy per-tryb, integrację z Tablistą wielokolumnową oraz własne Placeholdery dostarczane do zewnętrznych wtyczek.

---

## 1. Architektura Scoreboardów (Anti-Flicker)

Zarządzanie boczną tablicą wyników (Scoreboard) zostało zaprojektowane z myślą o maksymalnej wydajności i płynności:

### Mechanizm FastBoard
Klasyczny problem "migotania" (flickering) ekranu podczas odświeżania tablicy wynika z ciągłego niszczenia i tworzenia nowych celów (`Objectives`). Zamiast tego zaimplementowaliśmy w `core-paper/board/FastBoard.java` system oparty na zespołach (`Teams`):
- Tworzymy do 15 unikalnych zespołów.
- Każdy zespół posiada sztucznego gracza (odpowiadającego kodom kolorów np. `&0`, `&1`).
- Odświeżanie polega wyłącznie na zmianie atrybutu `prefix` w danym zespole.
- **Wynik:** 100% płynne odświeżanie statystyk, niewidoczne dla klienta obciążenie.

### CoreApi & ScoreboardService
- Serwis `ScoreboardService.java` rejestruje zadanie (Task) uruchamiane asynchronicznie co 20 ticków (1 sekunda).
- Zadanie to iteruje po wszystkich graczach i aktualizuje ich osobiste tablice `FastBoard`.
- Sam układ (tytuł i linie) nie jest twardo zakodowany w rdzeniu! Rdzeń `core-paper` udostępnia w swoim API (`CoreApi`) metody:
  ```java
  CoreApi.get().setScoreboardTemplate(title, lines);
  ```
- **Pluginy trybów** (np. `mode-hub`, `mode-survival`) podczas swojego ładowania czytają z własnych `config.yml` wygląd scoreboardu i rejestrują go w rdzeniu. Dzięki temu każdy tryb może mieć kompletnie inną tablicę bez powielania pętli odświeżających.

---

## 2. Wydajne liczenie graczy (Heartbeat, nie keys)

Aby dostarczyć na Scoreboard i Tablistę dokładną liczbę graczy na całej sieci (lub na konkretnym trybie), musieliśmy rozwiązać problem przeciążania bazy danych Redis.
Tradycyjne podejście: skanowanie bazy komendą `KEYS session:*` kilkadziesiąt razy na sekundę przez serwery - co bywało powodem ogromnych lagów na serwerach proxy.

### Nasze Rozwiązanie:
Klasa `ShardRegistry` odczytuje statystyki używając mechanizmu **Heartbeat**:
- Każdy Shard i tak co 5 sekund raportuje swój stan (`softcap`, `hardcap`, `players`) do słownika Redis HashTable.
- Metody `getGlobalPlayers()` oraz `getPlayersInMode(String mode)` wyciągają sumę z tych istniejących słowników.
- Czas działania tego algorytmu wynosi blisko `O(1)` w stosunku do liczby graczy, dzięki czemu możemy zliczać tysiące graczy w milisekundach.

---

## 3. Integracja Tablisty i Nametagów

W związku ze standardem wyświetlania sztywnych, 4-kolumnowych tablist (`Layout Tablist`), zrezygnowaliśmy z implementacji ręcznego wysyłania pakietów `ClientboundPlayerInfoUpdatePacket` na rzecz integracji z zewnętrznym, wysoce zoptymalizowanym systemem **TAB (autorstwa NEZNAMY)**.

### PlaceholderAPI Expansion
Nasz rdzeń `core-paper` automatycznie rejestruje w systemie rozszerzenie do PAPI (`PlaceholderAPIExpansion.java`). Oznacza to, że każdy plugin na serwerze (w tym zewnętrzny TAB czy Hologramy) może korzystać z naszych wewnętrznych, wysokowydajnych zmiennych:
- `%elcartel_global_online%` - liczba wszystkich graczy na serwerze.
- `%elcartel_shard_id%` - nazwa aktualnego serwera z env.
- `%elcartel_online_mode_<nazwa>%` - np. `%elcartel_online_mode_survival%`. Liczba graczy zawężona do podanego trybu (bardzo przydatne do listy w menu Huba lub do Tablisty).

### LuckPerms & Prefixy
Oryginalnie wdrożony został system czytania grup przez API LuckPerms (zabezpieczony przed crashami klasy przez `LuckPermsHook.java`). 
Ze względu na użycie pluginu TAB, prefiksy nad głowami oraz odpowiednie sortowanie na podstawie wagi rangi w plikach konfiguracyjnych TABa jest zautomatyzowane na poziomie wtyczki TAB, bez ingerowania w pakiety po stronie `core-paper`.
Warto pamiętać, aby w sekcji `sorting-types` pliku konfiguracyjnego `TAB` znajdowały się absolutnie wszystkie możliwe wewnętrzne nazwy grup (np. `default`, `root`), w przeciwnym razie operatorzy lub gracze pozbawieni głównej grupy będą domyślnie zrzucani na dół hierarchii Tablisty z ostrzeżeniem w konsoli.

---

## 4. Dodawanie nowego trybu z obsługą UI

Tworząc nowy tryb (np. `mode-bedwars`):
1. Tworzymy główną klasę rozszerzającą `JavaPlugin`.
2. Dodajemy sekcję `scoreboard:` do `src/main/resources/config.yml` tego trybu.
3. W metodzie `onEnable()` wstrzykujemy wygląd:
   ```java
   if (CoreApi.get() != null) {
       CoreApi.get().setScoreboardTemplate(
           getConfig().getString("scoreboard.title"),
           getConfig().getStringList("scoreboard.lines")
       );
   }
   ```
4. Na tabliście używamy nowej zmiennej: `%elcartel_online_mode_bedwars%`.
5. Serwer samoistnie zacznie asynchronicznie przesyłać widok do wszystkich graczy przebywających na instancjach tego trybu!
