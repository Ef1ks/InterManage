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

## Powiadomienia push (wnioski, różnice w czasie pracy)

Kod jest gotowy (`src/lib/push.ts`, `expo-notifications`), ale powiadomienia ruszą dopiero po konfiguracji. Do tego
czasu aplikacja działa bez nich: różnice i wnioski widać na ekranie Wnioski, a backend wypisuje wiadomości w logach
(`[PUSH] …`).

1. Projekt EAS: `npx eas init` w `mobile/` dopisze `extra.eas.projectId` w `app.json`. Bez niego aplikacja w ogóle
   nie prosi o token.
2. Android: projekt Firebase z aplikacją `pl.intermanage.mobile`, plik `google-services.json` w `mobile/` i
   `"googleServicesFile": "./google-services.json"` w `android` w `app.json`; w EAS (credentials, Android) klucz
   konta usługi FCM V1 z Firebase.
3. iOS: klucz APNs w EAS (`npx eas credentials`, zrobi go sam przy pierwszym buildzie).
4. Nowy build aplikacji (`npx eas build`), bo moduł powiadomień jest natywny. W Expo Go na Androidzie powiadomienia
   push nie działają.
5. Na serwerze `PUSH_ENABLED=true` w `/opt/intermanage/.env` (wzór `deploy/.env.example`) i restart.

Instalacja zależności: `npm install --legacy-peer-deps` (konflikt peer dependencies `@radix-ui` z React 19.3 w
expo-router był wcześniej, nie dotyczy powiadomień).
