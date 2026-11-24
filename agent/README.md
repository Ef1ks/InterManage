# Intermanage – agent sklepu

Mały program na komputer w sklepie (Windows 10/11, .NET Framework 4.8). Intermanage stoi poza sklepem i nie widzi
dysku sieciowego (`N:\…`), więc agent przejmuje styk z folderami sklepu:

- **zapisuje pliki dla programu sklepu** (protokoły, zmiany cen, CADBEZP, etykiety, zamówienia, .PRO, .lst, XML faktur)
  w 1-2 s po kliknięciu „Zapisz” w Intermanage: czeka na nie stałym połączeniem (long polling), plik zapisuje
  atomowo i potwierdza zapis, a ekran w przeglądarce pokazuje potwierdzenie z godziną;
- **pilnuje folderów** i od razu zgłasza zmiany: pliki cen z kasy TRACEART (z podfolderem BACKUP), wczytanie zmiany cen
  przez program sklepu (`.traite0` → `.Traite1`) i dziennik ATHOS (`Histo.Log`);
- **wgrywa raporty Importu danych** (od wersji 1.1): pilnuje folderu każdego codziennego raportu (folder z ustawień
  importu, bez niego folder ścieżki zapisu makra „Zapis z Mercalys”), nowy plik wysyła w kawałkach, a serwer wgrywa go
  tym samym importem co „Odśwież dane”; wgrany raport usuwa z folderu następnego dnia (tylko plik z tą samą datą
  zmiany, nowszy raport pod tą samą nazwą zostaje).

Agent działa tylko w sklepie, który w Intermanage wybrał **Ustawienia sklepu → Agent sklepu → Praca z folderami przez
agenta**. Bez tego (albo gdy agent jest offline) wszystko działa jak dotąd, w przeglądarce.

## Instalacja

1. W Intermanage: Ustawienia sklepu → Agent sklepu → wpisz nazwę (np. „Komputer biuro”) → **Dodaj agenta**. Skopiuj
   klucz (pokazuje się tylko raz).
2. Rozpakuj folder agenta w dowolnym miejscu w profilu użytkownika (np. `%LOCALAPPDATA%\Programs\IntermanageAgent`)
   i uruchom `IntermanageAgent.exe`. Bez instalatora i bez uprawnień administratora.
3. W oknie ustawień zostaw adres `https://intermanage.pl`, wklej klucz i kliknij **Sprawdź i zapisz**.
4. Przy zegarze pojawi się ikona „Intermanage – agent sklepu”: zielona = działa, bursztynowa = dysk albo serwer
   niedostępny (agent ponawia sam), czerwona = serwer odrzucił klucz (wklej nowy), szara = brak klucza.
5. W Intermanage przełącz „Praca z folderami przez agenta”. Ścieżki folderów agent bierze z ustawień sklepu
   (ścieżki folderów w oknach zapisu, Ustawienia sklepu → Protokoły i zmiana cen).

## Agent na kilku komputerach sklepu

Agenta można uruchomić na każdym komputerze sklepu, każdy z **własnym kluczem** (jeden klucz na kilku komputerach
daje jednego agenta). Pliki z kolejki zapisuje wtedy dowolny włączony komputer. Folderów pilnuje (ceny z kasy, zmiany
cen, dziennik ATHOS, raporty Importu danych) i dane z Mercalys przy starcie aktualizuje tylko **główny agent**. Wybiera
go serwer: pierwszy zgłaszający się komputer dostaje dzierżawę na 60 s, przedłużaną każdym żądaniem. Gdy główny
przestanie odpowiadać, rolę przejmuje następny. Komputer oznaczony w Ustawieniach sklepu jako **Preferowany**
(np. biuro, nie kasa) odbiera tę rolę od razu, gdy tylko jest online. Stan ikony mówi „główny agent” albo
„zapasowy”. Agent sprzed 1.3 nie zna tej roli, ale serwer i tak pomija zdarzenia z folderów od agenta, który nie jest
główny.

Menu ikony: Stan, Otwórz Intermanage, Ustawienia, Sprawdź teraz (pełny przegląd folderów), Pokaż logi, Uruchamiaj
z Windows, Zakończ.

## Co agent robi, a czego nie

Robi:

- łączy się **tylko wychodząco** po HTTPS z adresem z ustawień (TLS 1.2+, pełna walidacja certyfikatu, proxy
  systemowe); nie nasłuchuje na żadnym porcie;
- zapisuje **tylko** w folderach, które serwer podał dla jego klucza; nazwa pliku jest sprawdzana (bez `\`, `/`, `..`,
  znaków i nazw zastrzeżonych, do 200 znaków), a pełna ścieżka musi leżeć w folderze;
- usuwa **tylko** raporty Importu danych, które serwer wgrał przed dzisiejszym dniem, w ich folderze raportu;
- zapisuje atomowo (`<nazwa>.intermanage-tmp`, potem zmiana nazwy) i nadpisuje plik o tej samej nazwie, jak
  przeglądarka (podmiana dopiero po zapisaniu całego pliku tymczasowego, więc program sklepu nie zobaczy połowy pliku);
- uruchamia się z Windows przez `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` (wyłączane w menu ikony);
- przy starcie (od 1.2.0, wyłączane w Ustawieniach) sprawdza daty plików w folderach raportów Importu danych i gdy
  któryś nie jest z dzisiaj, pokazuje na wierzchu okno „AKTUALIZACJA DANYCH – POCZEKAJ DO POTWIERDZENIA”, pobiera
  z serwera makro zapisu raportów z Mercalys (to samo co „Zaktualizuj wszystkie pliki”), wkłada je do schowka
  i naciska lewy Alt+E, a okno trzyma, aż serwer potwierdzi wgranie każdego pliku (menu ikony: „Aktualizuj dane
  z Mercalys” robi to na żądanie dla wszystkich raportów). Gdy serwer albo dysk z folderami raportów nie odpowiada,
  próbuje co minutę, aż wróci (od 1.7.1; na część folderów czeka 10 min, potem aktualizuje pozostałe);
- trzyma ustawienia w `%LOCALAPPDATA%\Intermanage\Agent\settings.json`, klucz i hasło Mercalys zaszyfrowane DPAPI
  dla bieżącego użytkownika; dziennik w `%LOCALAPPDATA%\Intermanage\Agent\logs` (plik na dzień, 14 dni, bez treści plików i klucza);
- ma przy sobie odtwarzacz makr (`player\player.exe` obok agenta, z plikiem `player\agent-mode`, `PlayerHost`):
  uruchamia go z folderu agenta i co 20 s sprawdza, czy działa. Odtwarzacz z `agent-mode` nie ma własnej ikony
  (pokazuje ją tylko w trakcie makra i przy powiadomieniu), nie dopisuje się do startu Windows i usuwa dawny wpis
  `IntermanageMakra`; agent zamyka osobny odtwarzacz (dawny `player.exe`, `AutoJob.exe`), bo dwa wykonałyby makro dwa
  razy. Wersja i pomoc (Alt+E, Esc, Alt+R) są w menu ikony agenta, a „Zakończ” zamyka też odtwarzacz. Do 1.5.0
  odtwarzacz był wypakowywany do `%LOCALAPPDATA%`, ale komputery sklepu nie pozwalają uruchamiać programów z AppData
  („Odmowa dostępu”), więc od 1.5.1 leży w folderze agenta; gdy Windows i tak go zablokuje, agent zapisuje to raz
  w dzienniku, nie ponawia i pokazuje w menu ikony. Agent bez `player\player.exe` działa jak dawniej z osobnym
  odtwarzaczem.

- wykonuje **makra zlecone z przeglądarki** (od 1.7.0, na razie „Zbierz brakujące ITM8” i „Uzupełnij ITM8” z dostawy
  z bazy): co 3 s zgłasza serwerowi, ile sekund nikt nie ruszał myszy i klawiatury, czy ekran jest zablokowany, czy
  działa AutoJob (od 0.64) i czy trwa makro. Serwer wybiera najdłużej nieużywany komputer, a agent pokazuje „Makro
  rozpocznie się za 5…” (15 s, gdy ktoś ruszał komputer w ostatnich 2 min) z „Anuluj”, które oddaje makro kolejnemu
  komputerowi (ruch myszy nie anuluje od 1.7.2). Wyłączony Mercalys makro uruchamia z położenia w ustawieniach agenta
  („Program Mercalys”), a nieznane agent bierze z działającego Mercalys,
  z `N:\AvisoConfig\UTILITAIRES\ClientLourd\Mercalys.exe` (od 1.7.3) albo ze skrótu na pulpicie. Treść
  składa serwer, agent wkłada ją do schowka i naciska Alt+E, a wynik odtwarzacz zapisuje w
  `%LOCALAPPDATA%\Intermanage\Agent\makra` (plik znika po odesłaniu). Komputer z zablokowanym ekranem makra nie dostanie.

Nie robi:

- nie wymaga administratora (`asInvoker`), nie instaluje usługi, nie pisze do HKLM ani Program Files;
- nie uruchamia PowerShella, cmd ani skryptów i nie uruchamia innego kodu niż odtwarzacz makr ze swojego exe i swoją
  nowszą wersję z automatycznej aktualizacji (niżej), sprawdzoną sumą SHA-256 i podpisem;
- nie klika w Mercalys sam: makra wykonuje odtwarzacz, a agent tylko wkłada makro aktualizacji do schowka i naciska
  za pracownika Alt+E;
- nie jest pakowany, zaciemniany ani szyfrowany (UPX itp.), więc programy antywirusowe widzą zwykły exe.

## Pobieranie nowej wersji (od 1.6.0)

Pozycja w Intermanage → Do pobrania z włączonym przełącznikiem **Paczka agenta sklepu** to wersja, do której pobierają
się wszystkie agenty (najwyżej jedna taka pozycja). Plik to ZIP z zawartością `bin\Release\net48` (może być w jednym
folderze), a wersja jak w `Intermanage.Agent.csproj` (np. `1.6.0`); serwer odrzuca paczkę bez `IntermanageAgent.exe`
albo bez wersji.

Serwer podaje wersję, SHA-256 i rozmiar paczki w każdej odpowiedzi `/hello` (co 5 min). Agent ze starszą wersją
**tylko pobiera** paczkę (ZIP) do folderu `aktualizacja` obok siebie (na dysku sieciowym pierwszy komputer pobiera,
reszta widzi gotowy plik), sprawdza rozmiar i SHA-256 i pokazuje przy ikonie „Nowa wersja … pobrana”. W menu ikony
„Pokaż pobraną aktualizację” otwiera ZIP w Eksploratorze.

**Rozpakowania i uruchomienia agent nie robi**, tylko człowiek, raz na sklep (od 1.7.5 bez zamykania agentów):

1. „Wyodrębnij wszystkie” przy ZIP-ie. Powstaje folder `agent-1.7.6` (w `aktualizacja` albo w folderze agenta; nazwa
   `1.7.6` też działa), więc żaden plik działającego agenta nie jest nadpisywany.
2. Agent na każdym komputerze w ciągu ok. 2 minut zauważa nowszą wersję (`VersionWatch`, wersja z pliku exe, po dwóch
   takich samych sprawdzeniach, żeby nie wziąć rozpakowywanego folderu), przestawia swój autostart i pokazuje w menu
   ikony „Uruchom nową wersję”. Bez kliknięcia nowa wersja rusza przy następnym logowaniu do Windows.
3. Uruchomiona nowa wersja (menu albo dwuklik) prosi działającą o zamknięcie (`Handover`); stara kończy makro oraz
   trwający zapis i wysyłkę (do 10 min), a nowa startuje sama i ustawia autostart na siebie.

Powrót: usuń folder nowej wersji i uruchom starą (autostart wskazujący usunięty plik działający agent sam cofa na
siebie). Starego folderu nie usuwaj, dopóki któryś komputer z niego działa. Agenci do 1.7.4 nie znają prośby
o zamknięcie: na nich trzeba ostatni raz kliknąć „Zakończ”.

Agent celowo nie rozpakowuje ani nie uruchamia żadnego programu: program ochronny komputerów sklepu (SentinelOne)
traktuje „rozpakuj exe i uruchom go” jak atak i zablokował tak agenta 1.5.0 razem z jego odtwarzaczem makr (10023,
23.11.2026). Samo pobranie pliku przez HTTPS i zapis do folderu jest dla niego zwykłą pracą agenta. **Z tego samego
powodu paczka nie zawiera odtwarzacza makr** (niepodpisany `player.exe` z AutoHotkey jest kasowany przez SentinelOne):
odtwarzacz AutoJob zostaje osobny, pobierany z „Do pobrania” przez przeglądarkę i uruchamiany ręcznie, a agent bez
`player\player.exe` obok siebie go nie zamyka.

## Budowanie## Budowanie

Wymaga .NET SDK (8 albo nowszego); biblioteki .NET Framework 4.8 przychodzą z pakietu
`Microsoft.NETFramework.ReferenceAssemblies`, bez Developer Packa.

Najpierw odtwarzacz makr (z katalogu projektu), bo agent bierze `player.exe` do środka przy budowaniu; bez niego
budowanie daje ostrzeżenie i agent jest bez odtwarzacza:

```powershell
.\build-player.ps1
# podpisz player.exe (niżej) PRZED budowaniem agenta: Smart App Control / zasady kontroli aplikacji blokują
# niepodpisany exe wypakowany do %LOCALAPPDATA%
cd agent
dotnet build -c Release
# wynik: agent\bin\Release\net48\ (IntermanageAgent.exe, IntermanageAgent.exe.config, biblioteki System.Text.Json)
```

Do „Do pobrania” trafia spakowany folder `bin\Release\net48`.

## Podpis Authenticode

Certyfikat do podpisu kodu leży poza repozytorium. Podpisujemy oba: najpierw `player.exe` (przed budowaniem agenta,
bo trafia do jego środka), potem po zbudowaniu agenta jego exe:

```powershell
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /f C:\certyfikaty\intermanage-code.pfx /p <hasło> player.exe
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /f C:\certyfikaty\intermanage-code.pfx /p <hasło> `
  agent\bin\Release\net48\IntermanageAgent.exe
signtool verify /pa agent\bin\Release\net48\IntermanageAgent.exe
```

(albo `/sha1 <odcisk>` dla certyfikatu z magazynu Windows / tokenu USB zamiast `/f` i `/p`). Wersję pliku zmienia się
w `Intermanage.Agent.csproj` (`Version`, `FileVersion`, `AssemblyVersion`); serwer pokazuje ją w Ustawieniach sklepu.

## Jak to działa z serwerem

`/api/store-agent/**`, klucz w `Authorization: Bearer`:

- `POST /hello` przy starcie i co 5 min: foldery zapisu (klucz folderu → ścieżka) i foldery do pilnowania;
- `GET /file-queue/wait?timeout=25` w pętli: pliki przydzielone temu agentowi (przydział ważny 2 min), potem
  `GET /file-queue/{id}/file`, zapis i `POST /file-queue/{id}/saved` albo `/failed`;
- `POST /events` z paczką zdarzeń (najpóźniej sekundę po wykryciu, co 5 min pełny przegląd), w odpowiedzi pliki,
  o które serwer prosi: TRACEART → `POST /price-files`, `Histo.Log` → `POST /athos-history`.

**Wolne łącze (np. 200 kB/s):** pliki cen z kasy idą spakowane gzipem paczkami do 10 plików i 2 MB, więc jedna wysyłka
trwa kilkanaście sekund (Cloudflare zrywa żądanie po ok. 100 s) i nie zatyka łącza sklepu. Dziennik ATHOS po pierwszej
wysyłce idzie tylko końcówką od poprzedniego końca (z zakładką 64 KB), też spakowany. Zapis plików do folderów i
zdarzenia to pojedyncze kB, więc wolne łącze ich nie spowalnia.

Przy niedostępnym serwerze przerwa rośnie do 60 s, przy niedostępnym dysku do 5 min; zdarzenia bez połączenia czekają
w pamięci (najwyżej 5000, resztę uzgodni pełny przegląd).
