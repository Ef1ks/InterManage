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
  zmiany, nowszy raport pod tą samą nazwą zostaje);
- **wczytuje pliki nowych artykułów** (od wersji 1.10): po zapisie pliku w folderze nowych artykułów (zakłada go sam,
  gdy go nie ma) serwer zleca makro wolnemu komputerowi sklepu, a agent przed startem sprawdza, czy plik jest
  w folderze. Plik wrzucony ręcznie makra nie uruchamia. Pliki `CADBEZP*.xls` z poprzednich dni główny agent usuwa
  z tego folderu codziennie (treść zostaje na serwerze).

- **wprowadza zmiany cen** (od wersji 1.11): gdy zmiana cen pojawi się w folderze, serwer zleca makro preferowanemu
  komputerowi sklepu (bez preferowanego dowolnemu wolnemu). Okno „Zmiana cen za 2” rusza po 2 s bez ruchu myszy
  i klawiatury (ruch zaczyna odliczanie od nowa, drżenie myszy do 8 pikseli się nie liczy, najdłużej 20 s); „Anuluj”
  odkłada zmianę o 10 minut. Wyłączony albo zablokowany komputer dostaje ją, gdy wróci (do 24 h).
- **ponawia aktualizację danych po błędzie** (od wersji 1.12, odtwarzacz od 0.69): plik, który się nie udał, makro robi
  od nowa (do 3 prób) i przechodzi do następnych, a czego dalej brakuje albo co serwer odrzucił, agent drukuje jeszcze
  raz. Okno aktualizacji ma „Przerwij” w trakcie i „Spróbuj ponownie” po błędzie (tylko brakujące raporty). Z odtwarzaczem
  0.64-0.68 ponawia sam agent, nowym makrem od raportu z błędem. Raport wysyła dopiero, gdy Excel skończył go zapisywać.

Każdą z tych rzeczy sklep włącza i wyłącza w Intermanage (Ustawienia sklepu → Agent sklepu, osoba z edycją wprowadzania
danych), a na jednym komputerze w oknie ustawień agenta (od 1.12.2): zapis plików, makra, aktualizacja przy starcie
i start z Windows.

Agent działa tylko w sklepie, który w Intermanage wybrał **Ustawienia sklepu → Agent sklepu → Praca z folderami przez
agenta**. Bez tego (albo gdy agent jest offline) wszystko działa jak dotąd, w przeglądarce.

## Instalacja

1. W Intermanage: Ustawienia sklepu → Agent sklepu → wpisz nazwę (np. „Komputer biuro”) → **Dodaj agenta**. Skopiuj
   klucz (pokazuje się tylko raz).
2. Rozpakuj paczkę agenta raz na sklep do `P:\Production\INTERMANAGE\AGENT` (exe razem z plikami .dll i .config)
   i uruchom stamtąd `IntermanageAgent.exe`. Bez instalatora i bez uprawnień administratora.
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
- uruchamia się z Windows przez `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` (wpisuje się przy
  pierwszym zapisie ustawień, przełącznik jest w menu ikony i w ustawieniach). Od 1.12.3 agent nie przywraca usuniętego
  wpisu i nie zmienia go w tle, bo tak zachowuje się złośliwy program;
- przy starcie (od 1.2.0, wyłączane w Ustawieniach) sprawdza daty plików w folderach raportów Importu danych i gdy
  któryś nie jest z dzisiaj, pokazuje na wierzchu okno „AKTUALIZACJA DANYCH – POCZEKAJ DO POTWIERDZENIA”, pobiera
  z serwera makro zapisu raportów z Mercalys (to samo co „Zaktualizuj wszystkie pliki”), wkłada je do schowka
  i naciska lewy Alt+E, a okno trzyma, aż serwer potwierdzi wgranie każdego pliku (menu ikony: „Aktualizuj dane
  z Mercalys” robi to na żądanie dla wszystkich raportów). Gdy serwer albo dysk z folderami raportów nie odpowiada,
  próbuje co minutę, aż wróci (od 1.7.1; na część folderów czeka 10 min, potem aktualizuje pozostałe);
- trzyma ustawienia w `%LOCALAPPDATA%\Intermanage\Agent\settings.json`, klucz i hasło Mercalys zaszyfrowane DPAPI
  dla bieżącego użytkownika; dziennik w `%LOCALAPPDATA%\Intermanage\Agent\logs` (plik na dzień, 14 dni, bez treści plików i klucza);
- bierze czasy i limity z serwera (od 1.13.0, `Limits`, `limits` w `/hello`: czekanie na raporty, odliczanie, paczki
  wysyłki), w granicach zapisanych w kodzie, więc ich zmiana nie wymaga nowej wersji agenta;

- wykonuje **makra zlecone z przeglądarki** (od 1.7.0, na razie „Zbierz brakujące ITM8” i „Uzupełnij ITM8” z dostawy
  z bazy): czeka na makro jednym długim żądaniem (od 1.9.0; wcześniej pytał co 3 s) i zgłasza serwerowi, ile sekund nikt nie ruszał myszy i klawiatury, czy ekran jest zablokowany, czy
  działa AutoJob (od 0.64) i czy trwa makro. Serwer wybiera najdłużej nieużywany komputer, a agent pokazuje „Makro
  rozpocznie się za 5…” (pliki nowych artykułów 2 s) z „Anuluj”, które oddaje makro kolejnemu
  komputerowi (ruch myszy nie anuluje od 1.7.2). Wyłączony Mercalys makro uruchamia z położenia w ustawieniach agenta
  („Program Mercalys”), a nieznane agent bierze z działającego Mercalys,
  z `N:\AvisoConfig\UTILITAIRES\ClientLourd\Mercalys.exe` (od 1.7.3). Treść
  składa serwer, agent wkłada ją do schowka i naciska Alt+E, a wynik odtwarzacz zapisuje w
  `%LOCALAPPDATA%\Intermanage\Agent\makra` (plik znika po odesłaniu). Komputer z zablokowanym ekranem makra nie dostanie.
  Od 1.8.0 po makrze „Wprowadź dostawę” agent wysyła też bony dostawy zapisane przez to makro w folderze z ustawień
  sklepu (domyślnie `P:\Production\INTERMANAGE\EXCEL`; potrzebny AutoJob 0.67).

Nie robi:

- nie wymaga administratora (`asInvoker`), nie instaluje usługi, nie pisze do HKLM ani Program Files;
- nie uruchamia PowerShella, cmd, skryptów ani żadnego programu poza przeglądarką i Eksploratorem, otwieranymi
  kliknięciem w menu ikony (od 1.12.3 także nowej wersji agenta nie uruchamia);
- nie zamyka innych programów i nie przegląda ich: otwiera tylko proces odtwarzacza makr (po nazwie `player*`,
  `autojob*`) i Mercalys, żeby odczytać ścieżkę pliku (od 1.12.3);
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

### Stała ścieżka (zalecane)

Agent leży na dysku sieciowym w każdym sklepie w tym samym miejscu, `P:\Production\INTERMANAGE\AGENT`, a każdy komputer
uruchamia się z Windows stamtąd (raz dwuklik na komputerze, agent wpisuje wtedy tę ścieżkę do autostartu). Nową wersję
wgrywa człowiek raz na sklep, także przy działających agentach (sprawdzone na P: w 10023, 25.11.2026: nazwę działającego
pliku da się zmienić):

1. `IntermanageAgent.exe` → zmień nazwę na `IntermanageAgent-stary.exe` (zeszły stary plik najpierw usuń).
2. Z pobranej paczki `aktualizacja\agent-….zip` skopiuj do folderu nowy `IntermanageAgent.exe`. Biblioteki DLL zmieniają się
   rzadko; gdy opis wersji o tym mówi, podmień wszystkie pliki przy wyłączonych komputerach.
3. Działające komputery pracują dalej na starym pliku (menu ikony: „Wersja … ruszy po ponownym włączeniu komputera”),
   nową biorą przy następnym włączeniu. W Intermanage widać, które jeszcze czekają.

Tak samo AutoJob, w tym samym folderze: `P:\Production\INTERMANAGE\AGENT\AutoJob.exe` (od 0.71 pyta raz, czy startować z Windows z tego
miejsca, i nie zapisuje wpisu sam).

### Foldery wersji

**Rozpakowania i uruchomienia agent nie robi**, tylko człowiek, raz na sklep (od 1.7.5 bez zamykania agentów):

1. „Wyodrębnij wszystkie” przy ZIP-ie. Powstaje folder `agent-1.7.6` (w `aktualizacja` albo w folderze agenta; nazwa
   `1.7.6` też działa), więc żaden plik działającego agenta nie jest nadpisywany.
2. Agent na każdym komputerze w ciągu ok. 2 minut zauważa nowszą wersję (`VersionWatch`, wersja z pliku exe, po dwóch
   takich samych sprawdzeniach, żeby nie wziąć rozpakowywanego folderu) i pokazuje w menu ikony „Pokaż nową wersję”
   (plik w Eksploratorze). Autostartu nie zmienia i niczego nie uruchamia (od 1.12.3).
3. Uruchomiona dwuklikiem nowa wersja prosi działającą o zamknięcie (`Handover`); stara kończy makro oraz
   trwający zapis i wysyłkę (do 10 min), a nowa startuje sama i ustawia autostart na siebie.

Powrót: usuń folder nowej wersji i uruchom starą dwuklikiem (ustawi autostart na siebie). Starego folderu nie usuwaj, dopóki któryś komputer z niego działa. Agenci do 1.7.4 nie znają prośby
o zamknięcie: na nich trzeba ostatni raz kliknąć „Zakończ”.

Agent celowo nie rozpakowuje ani nie uruchamia żadnego programu: program ochronny komputerów sklepu (SentinelOne)
traktuje „rozpakuj exe i uruchom go” jak atak i zablokował tak agenta 1.5.0 razem z jego odtwarzaczem makr (10023,
23.11.2026). Samo pobranie pliku przez HTTPS i zapis do folderu jest dla niego zwykłą pracą agenta. **Z tego samego
powodu paczka nie zawiera odtwarzacza makr** (niepodpisany `player.exe` z AutoHotkey jest kasowany przez SentinelOne):
odtwarzacz AutoJob zostaje osobny, pobierany z „Do pobrania” przez przeglądarkę i uruchamiany ręcznie, a agent go nie
uruchamia ani nie zamyka.

25.11.2026 w 10023 oba komputery zamilkły kilka minut po przejściu na 1.12.2 (1.10.0 w 09575 pracował dalej), dlatego
1.12.3 nie ma już kodu uruchamiania i zamykania odtwarzacza (`PlayerHost`), przeglądania wszystkich programów, sprawdzania
pulpitu, przeglądania skrótów, uruchamiania nowej wersji ani przywracania autostartu.
1.12.0 działał, a 1.12.1 różnił się od niego tylko przywracaniem wpisu autostartu przy starcie (`EnsureAutostart`), więc
to najpewniej ono. Trwałe rozwiązanie to podpis Authenticode i wyjątek ustawiony przez administratora
programu ochronnego; nie omijamy go.

## Budowanie

Wymaga .NET SDK (8 albo nowszego); biblioteki .NET Framework 4.8 przychodzą z pakietu
`Microsoft.NETFramework.ReferenceAssemblies`, bez Developer Packa.

```powershell
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
- `POST /macro-jobs/wait` w pętli (od 1.9.0): stan komputera i czekanie na makro do 25 s (do 55 s, gdy ekran jest
  zablokowany, nie ma AutoJob albo trwa makro); zmianę stanu agent sprawdza u siebie co 2 s i zgłasza nowym żądaniem,
  które zastępuje poprzednie. Starszy agent pyta `POST /macro-jobs/poll` co 3 s;
- `POST /events` z paczką zdarzeń (najpóźniej sekundę po wykryciu, co 5 min pełny przegląd), w odpowiedzi pliki,
  o które serwer prosi: TRACEART → `POST /price-files`, `Histo.Log` → `POST /athos-history`.

**Wolne łącze (np. 200 kB/s):** pliki cen z kasy idą spakowane gzipem paczkami do 10 plików i 2 MB, więc jedna wysyłka
trwa kilkanaście sekund (Cloudflare zrywa żądanie po ok. 100 s) i nie zatyka łącza sklepu. Dziennik ATHOS po pierwszej
wysyłce idzie tylko końcówką od poprzedniego końca (z zakładką 64 KB), też spakowany. Zapis plików do folderów i
zdarzenia to pojedyncze kB, więc wolne łącze ich nie spowalnia.

Przy niedostępnym serwerze przerwa rośnie do 60 s, przy niedostępnym dysku do 5 min; zdarzenia bez połączenia czekają
w pamięci (najwyżej 5000, resztę uzgodni pełny przegląd).
