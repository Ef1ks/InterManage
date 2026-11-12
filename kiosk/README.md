# Intermanage Kiosk

Aplikacja na tablet przy wejściu (Expo SDK 58, Expo Router, Android): pracownicy odbijają kartą wejście i wyjście,
a kierownik przypisuje i programuje karty NFC. Nic więcej.

## Uruchomienie

NFC (`react-native-nfc-manager`) to moduł natywny, więc aplikacja **nie działa w Expo Go**: potrzebny jest własny
build (dev client albo .apk).

```bash
cd kiosk
npm install
npx expo run:android                                   # telefon/tablet na USB z debugowaniem, build lokalny (Android SDK)
npx expo start                                          # potem już tylko serwer JS dla zainstalowanego dev clienta
npx eas-cli@latest build -p android --profile preview   # albo .apk z chmury do zainstalowania z linku
npx tsc --noEmit && npx expo lint
```

Identyfikator: `pl.intermanage.kiosk`. Foldery `android/` i `ios/` generuje `npx expo prebuild`, nie trzymamy ich
w repozytorium. Prettier: `npx prettier --config ../frontend/.prettierrc.json --write <pliki>`.

## Pierwsze uruchomienie

1. W przeglądarce: Zespół → Czas pracy → koło zębate → Czytniki → dodaj czytnik (np. „Kiosk wejście”). Klucz widać
   tylko raz.
2. W kiosku: adres serwera i klucz → „Sprawdź i zapisz” (`GET /api/time-clock/reader`). Kiosk zapamiętuje sklep
   i startuje też bez internetu.
3. Tryb kiosku: Ustawienia Androida → Bezpieczeństwo → Przypinanie aplikacji, potem przypiąć Intermanage Kiosk.

## Odbijanie

Ekran główny czyta karty stale: NFC urządzenia (tryb czytnika, bez dźwięku systemu) i czytnik USB na kablu (działa
jak klawiatura: niewidoczne pole bez klawiatury ekranowej łapie numer i Enter). `POST /api/time-clock/punches`
z kluczem czytnika; kierunek (wejście, wyjście) liczy serwer. Bez internetu odbicie z prawdziwą godziną czeka
w kolejce (SecureStore) i wychodzi samo, starsze najpierw. Ta sama karta w ciągu 5 s liczy się raz.

**Czytnik USB** często wypisuje UID dziesiętnie (10 cyfr, bajty odwrócone). Format wybiera się w Ustawieniach kiosku
(`lib/uid.ts`, „Sprawdź czytnik USB” pokazuje, co trafi na serwer), bo numer musi się zgadzać z UID z NFC.

## Tryb kart

Przytrzymanie „Karty” w rogu → logowanie kontem z Kadrami (edycja) i wglądem w Czas pracy w sklepie czytnika (jak
`StaffTimeController.CONFIGURE`). Token jest tylko w pamięci, tryb zamyka się po 3 min bez dotyku. Lista pracowników
i przypisanie to te same endpointy co w przeglądarce (`/stores/{id}/staff/time/settings`, `.../cards/{employeeId}`).

**Programowanie** (karty MIFARE Classic 1K, ISO 14443A): sektor 1, blok 4, klucz fabryczny `FFFFFFFFFFFF`, 16 bajtów
`IMRC` + wersja `01` + numer RCP (4 bajty, big-endian) + zera; zapis jest od razu czytany z powrotem. Pracownika
i tak wskazuje UID, więc zapis to oznaczenie karty do sprawdzenia bez serwera („Sprawdź kartę”), a nie klucz dostępu.
Zapis MIFARE Classic wymaga układu NFC od NXP; urządzenie bez niego (część Samsungów) przypisuje kartę samym UID
i mówi o tym. Czytnik USB też tylko przypisuje.
