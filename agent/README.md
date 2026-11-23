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
  z Mercalys” robi to na żądanie dla wszystkich raportów);
- trzyma ustawienia w `%LOCALAPPDATA%\Intermanage\Agent\settings.json`, klucz i hasło Mercalys zaszyfrowane DPAPI
  dla bieżącego użytkownika; dziennik w `%LOCALAPPDATA%\Intermanage\Agent\logs` (plik na dzień, 14 dni, bez treści plików i klucza).

Nie robi:

- nie wymaga administratora (`asInvoker`), nie instaluje usługi, nie pisze do HKLM ani Program Files;
- nie uruchamia PowerShella, cmd ani skryptów, nie pobiera ani nie uruchamia kodu, nie aktualizuje się sam
  (nowe wersje są w Intermanage → Do pobrania);
- nie klika w Mercalys i nie przechwytuje klawiatury ani myszy: makra wykonuje `player.exe`, a agent tylko wkłada
  makro aktualizacji do schowka i naciska za pracownika Alt+E;
- nie jest pakowany, zaciemniany ani szyfrowany (UPX itp.), więc programy antywirusowe widzą zwykły exe.

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

Certyfikat do podpisu kodu leży poza repozytorium. Po zbudowaniu:

```powershell
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
