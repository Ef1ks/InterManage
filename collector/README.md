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

Wystarczy dotnet SDK (bez Visual Studio 2008). Biblioteki referencyjne Compact Framework (`ref\`, poza gitem) skrypt
bierze z instalatora Microsoftu, którego podpis sprawdza.

Na Windowsie z kontrolą aplikacji niepodpisany exe się nie uruchomi. Program da się sprawdzić w kontenerze z Mono
(`mono:6.12` + Xvfb): jest „retargetable”, więc to ten sam plik co na kolektor. Do prób `ustawienia.txt` obok programu
z linią `server=http://127.0.0.1:8090` (zwykłe http tylko na tym samym komputerze).

## Instalacja na kolektorze

1. Na komputerze: Ustawienia sklepu → Kolektory → Pobierz instalator. Skopiuj `IntermanageSetup.exe` na kolektor
   (połączenie z komputerem, karta) i uruchom. Przeglądarka kolektora go nie pobierze: Windows CE 6.0 nie połączy się
   przez https, a http Cloudflare przekierowuje na https.
2. „Zainstaluj”, potem „Uruchom”. Gdy kolektor zgłosi brak .NET Compact Framework, zainstaluj najpierw
   `NETCFv35.wce.armv4.cab`.
3. Na podstawce: w przeglądarce Ustawienia sklepu → Kolektory → Dodaj kolektor, kod z 8 cyfr wpisz na kolektorze.
4. Zostaw na podstawce, aż pasek na dole pokaże „Dane aktualne”.
5. Skaner: DataWedge w tle (wpisuje kod jak klawiatura). Enter po kodzie nie jest wymagany.

Instalator wgrywa program do `\Application\Intermanage` (pamięć flash, zostaje po zimnym restarcie), zakłada skrót na
pulpicie i w `\Application\Startup` (start z systemem). Ponowne uruchomienie naprawia program, ustawienia i dane zostają.

```
\Application\Intermanage\Intermanage.exe      program startowy (nie zmienia się przy aktualizacjach)
\Application\Intermanage\1.1.0\Kolektor.exe   program, każda wersja w swoim folderze
\Application\Intermanage\wersja.txt           bieżąca, poprzednia, próba, zła
\Application\Intermanage\ustawienia.txt, katalog.dat, dziennik.txt
```

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
- **Aktualizacje** (`Updater.cs`, `shared/Versions.cs`): `status` mówi, jaka wersja jest na serwerze; nowsza pobiera się
  przed danymi (SHA-256, plik bez zmian jest kopiowany z bieżącej wersji) i uruchamia po minucie bez pracy na podstawce
  albo z Menu. Jest na próbie do pierwszego połączenia z serwerem: gdy nie da się jej uruchomić, 3 starty nie wystarczą
  albo przez 15 minut na podstawce się nie połączy, wraca poprzednia, a tej wersji kolektor już nie pobiera.
- **Bez cen zakupu i marż:** kolektor nie ma logowania osoby.
