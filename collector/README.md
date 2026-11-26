# Kolektor (Motorola MC3190, Windows CE 6.0)

Magazyn artykułów sklepu na kolektorze, działający **bez sieci**. Kolektor ma internet tylko na podstawce, więc tam
pobiera plik kartoteki i nowe wersje programu, a potem szuka po EAN-ie, ITM8, kodzie z etykiety wagi i nazwie.
.NET Compact Framework 2.0 (działa też na 3.5).

## Budowanie

```powershell
.\build.ps1                      # dist\IntermanageSetup.exe i pliki dla serwera (backend/src/main/resources/collector)
.\build.ps1 -TestVersion 1.0.9   # do obj\test, bez plików dla serwera (próba aktualizacji ze starszej wersji)
.\build.ps1 -BouncyCastle        # przebudowuje też lib\BouncyCastle.dll ze źródeł 1.8.10
```

Powstają: `Kolektor.exe` (program, `src`), `Intermanage.exe` (program startowy, `src/launcher`) i `IntermanageSetup.exe`
(instalator z nimi w środku, `src/setup`); wspólny kod jest w `src/shared`.

**Nowa wersja:** podnieś `$version` w `build.ps1`, zbuduj i wdróż razem z plikami w `backend/src/main/resources/collector`.
Po wdrożeniu kolektory wszystkich sklepów pobiorą ją same na podstawce. Bez podniesienia numeru kolektory nic nie pobiorą.

Kompilator to csc z .NET Framework 4 (w każdym Windowsie), celowo nie Roslyn z dotnet SDK: podejrzewamy, że .NET CF źle
wykonuje jego kod (na MC3190 TLS kończył się `unexpected_message` zaraz po certyfikacie serwera). Biblioteki referencyjne Compact Framework (`ref\`, poza gitem) skrypt
bierze z instalatora Microsoftu, którego podpis sprawdza.

Na Windowsie z kontrolą aplikacji niepodpisany exe się nie uruchomi. Program da się sprawdzić w kontenerze z Mono
(`mono:6.12` + Xvfb): jest „retargetable”, więc to ten sam plik co na kolektor. Do prób `ustawienia.txt` obok programu
z linią `server=http://127.0.0.1:8090` (zwykłe http tylko na tym samym komputerze).

## Instalacja na kolektorze

1. W przeglądarce kolektora wpisz `http://intermanage.pl/k` i uruchom pobrany `IntermanageSetup.exe`. Windows CE 6.0
   nie połączy się przez https, więc ten jeden adres idzie zwykłym http: nginx (`frontend/nginx.conf`) przekierowuje
   `/k` na `/k/IntermanageSetup.exe` = `/api/collector/setup`, a w Cloudflare reguła konfiguracji wyłącza dla `/k` i
   `/k/*` „Always Use HTTPS”. Przez http instalator da się po drodze podmienić; parowanie i dalsza praca idą już przez
   TLS programu. Bez sieci: Kolektory → Urządzenia → Pobierz instalator na komputerze i skopiuj plik na kolektor.
2. „Zainstaluj”, potem „Uruchom”. Gdy kolektor zgłosi brak .NET Compact Framework, zainstaluj najpierw
   `NETCFv35.wce.armv4.cab`.
3. Na podstawce: w przeglądarce Kolektory → Urządzenia → Dodaj kolektor, kod z 8 cyfr wpisz na kolektorze.
4. Zostaw na podstawce, aż pasek na dole pokaże „Dane aktualne”.
5. Skaner włącza sam program przez sterownik Motoroli (`Scanner.cs`, SCNAPI32.dll), gdy jest na wierzchu. Bez sterownika
   uruchamia ScanWedge z kolektora (wpisuje kod jak klawiatura, Enter nie jest wymagany). Stan: Menu → O programie.

Instalator wgrywa program do `\Application\Intermanage` (pamięć flash, zostaje po zimnym restarcie), zakłada skrót na
pulpicie i w `\Application\Startup` (start z systemem). Ponowne uruchomienie naprawia program, ustawienia i dane zostają.

```
\Application\Intermanage\Intermanage.exe      program startowy (nie zmienia się przy aktualizacjach)
\Application\Intermanage\1.1.0\Kolektor.exe   program, każda wersja w swoim folderze
\Application\Intermanage\wersja.txt           bieżąca, poprzednia, próba, zła
\Application\Intermanage\ustawienia.txt, katalog.dat, dziennik.txt
```

## Praca

- **1 Skanowanie:** karta artykułu. **2 Protokoły:** skan, ilość, Enter. **3 Ustaw promocje:** skan, cena albo
  przecena (−30/−50/−70%), koniec promocji w kalendarzu (jedno dotknięcie dnia).
- **Na podstawce** program pyta, czy wysłać listę (protokół do Kolektory → Dane z kolektorów, promocje do Promocje
  z kolektorów, z wyborem „ustaw w Mercalys” na komputerze sklepu). Lista zostaje na kolektorze; po wysyłce pytanie
  o usunięcie, a wysłana i niezmieniona znika sama po 2 godzinach. Kolejna wysyłka to same zmiany.
- **Lewy dolny róg:** w protokole „24: zł” / „24: kg” (koniec kodu 24 to cena albo waga) i kosz listy (z potwierdzeniem,
  po wyczyszczeniu w tym miejscu jest przywracanie).
- Artykuł z kodem sztuki i wielopaku (0,25 = 4-pak, 0,1666 = 6-pak) pyta, co liczysz; na protokół idzie główny kod.
- **Menu → Drukarka:** drukarki Bluetooth sklepu z Intermanage (Kolektory → Urządzenia, Drukarki Bluetooth). Cyfra,
  Enter albo skan kodu z adresem z wydruku konfiguracji drukarki łączy się z nią i pokazuje stan (papier, pokrywa,
  bateria); udane połączenie zapamiętuje drukarkę. Bez parowania w ustawieniach Bluetooth kolektora.

## Jak to działa

- **Szyfrowanie w programie** (`Http.cs`, TLS 1.2 z BouncyCastle): Windows CE 6.0 zna najwyżej TLS 1.0 i nie wysyła
  nazwy serwera (SNI), więc Cloudflare go odrzuca. Certyfikat sprawdza `Certificates.cs` na liście `korzenie.pem`
  (urzędy używane przez Cloudflare). Nowy urząd = nowa wersja programu z dopisanym korzeniem.
- **Zegar:** po zimnym restarcie kolektor ma datę fabryczną; wtedy certyfikat jest sprawdzany na dzień zbudowania
  programu, a zegar ustawia się z odpowiedzi serwera.
- **Dane** (`Catalog.cs`): układ pliku opisuje `CollectorCatalogFile` w backendzie; zmiana układu = zmiana w obu i nowy
  `MAGIC`. Kolektor nie wczytuje pliku do pamięci, tylko czyta go przez indeksy.
- **Pobieranie** (`Sync.cs`): po postawieniu na podstawce i co 5 minut pyta o wersję danych; plik (zlib) pobiera tylko
  nowy, sprawdza SHA-256 i dopiero wtedy podmienia.
- **Sieć** (`Network.cs`, Menu → Stan sieci): DNS z limitem czasu, a gdy nie działa (bywa po powrocie na podstawkę),
  zapamiętane adresy serwera; tożsamość i tak sprawdza certyfikat. Próba bez postępu przez 3 minuty zaczyna się od nowa.
- **Aktualizacje** (`Updater.cs`, `shared/Versions.cs`): `status` mówi, jaka wersja jest na serwerze; nowsza pobiera się
  przed danymi (SHA-256, plik bez zmian jest kopiowany z bieżącej wersji) i uruchamia po minucie bez pracy na podstawce
  albo z Menu. Jest na próbie do pierwszego połączenia z serwerem: gdy nie da się jej uruchomić, 3 starty nie wystarczą
  albo przez 15 minut na podstawce się nie połączy, wraca poprzednia, a tej wersji kolektor już nie pobiera.
- **Bez cen zakupu i marż:** kolektor nie ma logowania osoby.
