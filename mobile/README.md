# Intermanage mobile

Aplikacja na Androida i iOS (Expo SDK 58, Expo Router): logowanie kontem z aplikacji w przeglądarce, skan EAN
aparatem i statystyki artykułu jak w Magazynie (kartoteka, stan, ceny, wykres sprzedaży, dostawy, protokoły, ceny
i promocje).

## Uruchomienie

```bash
cd mobile
npm install
npx expo start          # kod QR: telefon z aplikacją Expo Go (aparat, SecureStore i SVG są w Expo Go)
npx tsc --noEmit        # typy
```

Domyślny serwer to `https://intermanage.pl` (`extra.apiUrl` w `app.json`). Na ekranie logowania „Serwer: …”
pozwala wpisać inny, np. `http://192.168.1.10:8080` (lokalny backend w tej samej sieci co telefon).

## Budowanie

Profile w `eas.json`: `production` (iOS do TestFlight / App Store, Android .aab do Google Play, numer builda rośnie
sam), `preview` (Android .apk do zainstalowania z linku), `development` (dev client).

```bash
npx eas-cli@latest login                                            # konto Expo
npx eas-cli@latest init                                             # raz: zapisuje projectId w app.json
npx eas-cli@latest build -p ios --profile production --auto-submit  # build + wysyłka do TestFlight
npx eas-cli@latest build -p android --profile preview               # .apk na Androida
```
Identyfikator aplikacji: `pl.intermanage.mobile`. Foldery `android/` i `ios/` generuje `npx expo prebuild`,
nie trzymamy ich w repozytorium.

## Logowanie

`POST /api/auth/mobile/login` zwraca token (ważny jak „Nie wylogowuj mnie”, 30 dni), który aplikacja trzyma
w SecureStore i wysyła w `Authorization: Bearer`. Odnowiony token backend oddaje w nagłówku `X-Auth-Token`.
Konto, które musi ustawić hasło, robi to najpierw w przeglądarce.
