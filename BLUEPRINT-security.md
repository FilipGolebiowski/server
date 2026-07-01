# Architektura Bezpieczeństwa (El Cartel Core)

Ten dokument zawiera zbiór krytycznych zasad i mechanizmów obronnych, które zostały zaimplementowane w jądrze (Core) naszej sieci, aby zapobiec popularnym exploitom architektonicznym.

## 1. Handoff Anti-Dupe (Zabezpieczenie przed kopiowaniem itemów)

Sieci oparte na wielu instancjach podrzędnych (Velocity + Paper) często cierpią na podatność określaną mianem *Inventory Duplication Race Condition*.

### Na czym polegał problem?
Kiedy gracz przechodzi z serwera `A` na serwer `B` (np. zmieniając sektor w świecie Survival):
1. Stary serwer `A` zapisuje zawartość jego ekwipunku, życia i doświadczenia do bazy MongoDB/Redis (metoda `ProfileService#prepareHandoff()`).
2. Proxy Velocity inicjuje fizyczne żądanie przełączenia do serwera `B`. To połączenie z racji fizyki sieci może potrwać do ~150ms.
3. W tym okienku (150ms) gracz fizycznie wciąż może się ruszać na starym serwerze `A` (nie został z niego wyrzucony, ponieważ Proxy czeka na potwierdzenie od serwera `B`).

Gdyby gracz w tamtym momencie wyrzucił z ekwipunku cenny miecz, to miecz ten wypadłby na ziemię. Ponieważ ekwipunek gracza **został już zrzucony** (z mieczem w środku) kilkadziesiąt milisekund wcześniej, serwer `A` nie zapisze tego usunięcia po raz kolejny. Gracz załaduje się na serwerze `B` z tym samym mieczem, podczas gdy jego kolega może podnieść skopiowany egzemplarz na serwerze `A`.

### Jak to zabezpieczyliśmy?
Aby wyeliminować ten wektor ataku, stworzony został dedykowany **AntiDupeListener**, który jest aktywowany równolegle do metody `prepareHandoff()`. Mechanizm działania:
* Profil gracza zostaje usunięty z lokalnej pamięci podręcznej i dodany do concurrentowego zbioru `transferring`.
* Wszystkie krytyczne interakcje ze środowiskiem (wymienione poniżej) stają się dla niego natychmiast anulowane.

Gracz zamrożony w stanie transferu:
- ❌ **Nie może upuścić przedmiotów** (`PlayerDropItemEvent`)
- ❌ **Nie podniesie przedmiotów z ziemi** (`EntityPickupItemEvent`)
- ❌ **Nie przeniesie ani nie przetasuje rzeczy w GUI (np. do skrzyni)** (`InventoryClickEvent`, `InventoryDragEvent`)
- ❌ **Nie użyje przedmiotu w ręce (np. wyrzucenie perły Kresu)** (`PlayerInteractEvent`)
- ❌ **Nie postawi/zniszczy bloków** (`BlockPlaceEvent`, `BlockBreakEvent`)
- ❌ **Nie użyje żadnych komend, by np. kogoś zabić czy wysłać pieniądze** (`PlayerCommandPreprocessEvent`)
- ❌ **Nie otrzyma obrażeń (nie zginie wyrzucając ekwipunek)** (`EntityDamageEvent`)

Całość skutecznie paraliżuje proces duplikacji, zamieniając gracza w bezbronnego obserwatora na kilkadziesiąt milisekund poprzedzających jego odłączenie od serwera.

### Globalne Zabezpieczenie Anti-Spam (Rate-Limiter GUI)
Oprócz Handoffu, `AntiDupeListener` posiada stałe zabezpieczenie na poziomie całego serwera, chroniące go przed Auto-Clickerami w inwentarzach (eksploity wysyłające pakiety w celu zablokowania głównego wątku Paper lub zduplikowania przedmiotu przy tzw. desyncu). 
Listener wymusza stały odstęp (`CLICK_COOLDOWN_MS = 350L`) pomiędzy akcjami typu `InventoryClickEvent` oraz `InventoryDragEvent` dla każdego gracza. Zapobiega to fizycznej możliwości sztucznego spamowania kliknięciami z częstotliwością przekraczającą 3 CPS (Clicks Per Second). Każda próba przekroczenia tego limitu skutkuje przerwaniem akcji oraz jasnym, wizualnym ostrzeżeniem wysyłanym do gracza ("Klikasz za szybko!").

---

## 2. Płatności Cross-Server i Ekonomia

Gospodarka serwera (saldo wirtualnej waluty) nie jest powiązana z "Profilem Gracza" ani przenoszona razem z nim. Wszelkie stany kont (dolarów) spoczywają wyłącznie we wspólnej i scentralizowanej bazie w pamięci (Redis).

Dzięki odizolowaniu warstwy gospodarczej, zapobiegliśmy jakimkolwiek wyłudzeniom związanym z opóźnionym zrzutem profilu. Kiedy gracz wykonuje komendę `/pay Notch 500`:
1. Kod asynchronicznie odwołuje się bezpośrednio do Redis.
2. Transakcja używa polecenia zmniejszającego w czasie rzeczywistym saldo wpłacającego z równoległym zwiększeniem salda odbiorcy.
3. System jest całkowicie niezależny od logowania i wylogowywania graczy z instancji Paper (nie da się na nim zasymulować "braku zapisu", ponieważ zapis to pojedyncza, błyskawiczna komenda wprowadzana od razu na bazie klastra).

Zabieg ten stanowi kamień węgielny zapobiegania "obchodzeniu" wymiany – nawet jeśli obaj gracze wylogują się w tej samej chwili lub ulegną gwałtownemu rozłączeniu (np. Crash proxy), ich salda pozostaną spójne.

---

## 3. Zabezpieczenia przeciw Crashom i Lag-Maszynom (Dynamiczna Konfiguracja)

Począwszy od nowszych wersji jądra, wszystkie kluczowe wartości zabezpieczające serwer są edytowalne w pliku `plugins/CorePaper/config.yml`. Wdrążono m.in. poniższe moduły:

### Anti-Spam i Crash Protection
- **Czat i Komendy:** Wbudowane filtry asynchroniczne (`AntiSpamListener`) odrzucające zbyt szybkie wiadomości na czacie i komendy.
- **Tarcza NBT:** Skaner (`CrashProtectionListener`) odrzucający przesyłanie wykraczających poza normę książek (za dużo stron, znaków na stronę) oraz tabliczek. Dodatkowo skanuje i blokuje znaki specjalne i z grup Private Use Area, używane przez Hack-Clienty do spęczniania pakietów (tzw. Book Exploits).

### Wykrywanie Lag-Maszyn (Anti-Lag)
- **Twardy Limit Zegarów Redstone:** Nasłuchiwanie na `BlockRedstoneEvent` w obrębie każdego chunka. Jeżeli częstotliwość zmian redstone przekroczy próg (np. 150 zmian w 5 sekund) w obrębie danego kawałka mapy (chunka), maszyna zostaje oznaczona jako "Lag-Maszyna", a pulsujący komponent zostaje fizycznie wydropiony na ziemię jako item. Zabezpiecza to serwer przed przeciążeniem wątku głównego.
- **Limit Zagęszczenia Bytów (Entity Cramming / Spawners):** Nakładanie górnego limitu zwierząt i potworów pojawiających się i rozmnażających naturalnie w jednym chunku (np. limit 50 zwierząt). Górny pułap ucina eventy spawnu, co odciąża serwer od kalkulacji sztucznej inteligencji bytów stłoczonych w pojedynczych kratkach na farmach.

---

## 4. Wykrywanie Oszustw Klienckich (AntiCheat Bridge)

Silnik wykorzystuje zewnętrzne i wiodące systemy antycheat (**GrimAC** do bezbłędnego śledzenia geometrii ruchu, oraz **Vulcan** do wykrywania zaawansowanych algorytmów walki - killaura/reach). Z uwagi na hybrydowy ekosystem serwera, wewnętrzne komendy kar tych pluginów omijają Proxy. Z tego powodu zbudowano klasę `AntiCheatBridge`.

### Jak działa AntiCheatBridge?
1. Antycheaty zamiast standardowego odcinania gracza komendą `/ban`, wywołują zarejestrowaną na Paperze ukrytą komendę `/ac-punish <gracz> <Nazwa Antycheata>`.
2. Most (Bridge) błyskawicznie chwyta to wywołanie i asynchronicznie odszukuje profil gracza.
3. Bana na ustalone z góry **14 dni** zostaje narzucony bezpośrednio do bazy danych (MongoDB).
4. Następuje natychmiastowe usunięcie oszusta z serwera po stronie Paper, oraz publikacja pakietu `core:punish` do szyny Redis.
5. Serwer Proxy (Velocity) obiera pakiet w trybie nasłuchu i jeśli gracz przeniósł się na inny tryb/lobby w tej samej chwili – wyrzuca go z całej sieci.

---

## 5. Analizator Ekonomii (Economy Guard)

Strażnik Ekonomii to asynchroniczny moduł działający wewnątrz silnika. Jego zadaniem jest wyłapywanie błędów ekonomicznych (bugi w sklepach, duping waluty, backdoory). Zabezpieczenie chroni serwer przed nagłą utratą wartości waluty.

- **Działanie:** System co kilkanaście sekund odpytuje bibliotekę Vault o stan konta wszystkich zalogowanych graczy na serwerze (np. Survival).
- **Progi Ostrzegawcze:** Jeżeli gracz zyska więcej niż ustalony limit (np. 100,000$ w minutę lub 1,000,000$ w 5 minut), zostaje wywołany alert.
- **Alert:** Ostrzeżenie jest zapisywane do konsoli, a wszyscy obecni na serwerze administratorzy otrzymują wizualne powiadomienie połączone z głośnym sygnałem dźwiękowym.
