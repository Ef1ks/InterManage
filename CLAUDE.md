# Intermanage

Aplikacja do zarządzania sklepem Intermarché. Dane będą pochodzić głównie z plików Excel `.xlsm` i formularzy ręcznych.

## Współpraca

- Odpowiadamy po polsku.
- Rozwijamy projekt małymi krokami: najpierw fundamenty (logowanie, role, użytkownicy), moduły biznesowe (raporty, importy Excel) dopiero na prośbę.
- Funkcje projektujemy pod codzienną pracę supermarketu: sprzedaż, stany magazynowe, zamówienia, pracownicy, straty.
- Skille projektu są w `.claude/skills/` (graphify, impeccable, emil-design-eng i inne Emila Kowalskiego, taste-skill). Przy pracy nad UI korzystamy z impeccable i emil-design-eng.

## Uruchomienie

```bash
docker compose up -d                       # PostgreSQL 17 na localhost:5433 (lokalny Postgres zajmuje 5432)
cd backend && ./mvnw spring-boot:run       # API na :8080, Flyway migruje schemat przy starcie
cd frontend && npm run dev                 # http://localhost:5173, proxy /api -> :8080
cd backend && ./mvnw test                  # testy integracyjne (Testcontainers, wymaga Dockera)
cd frontend && npm run build && npm run lint
```

Pierwsze konto właściciela tworzy `OwnerBootstrap`, gdy w bazie nie ma żadnego. Login to `OWNER_USERNAME`, hasło `OWNER_PASSWORD` (domyślnie `admin` / `admin`). Pusty `OWNER_PASSWORD` oznacza losowe hasło wypisane raz w logach backendu.

## Architektura

- **backend/**: Spring Boot 4.1, Java 25, Spring Security 7, JPA + Flyway (`src/main/resources/db/migration`), Jackson 3 (`tools.jackson`). Schemat zmieniamy tylko migracjami (`ddl-auto=validate`).
- **frontend/**: Vite + React 19 + TS, shadcn/ui (wariant base-nova, czyli **Base UI, nie Radix**: zamiast `asChild` jest `render={<Button />}`, `Select` dostaje `items` do wyświetlania etykiet), Tailwind 4, react-router 7, react-hook-form.
  - **TanStack Query** trzyma wszystkie dane z API (w tym zalogowanego użytkownika: `useMe`). **Redux** służy tylko do stanu UI (`store/uiSlice.ts`). Danych z serwera nie kopiujemy do Reduxa.
  - Formularze składamy z `components/form` (`Form`, `TextField`, `SelectField`, `SwitchField`, `SegmentedField`, `FormActions`, `FormNote`). Pola mają pływającą etykietę. `Button` ma `loading` i rozmiar `xl` dla głównej akcji. Nie składamy pól ręcznie z `Input` + `Label`.
  - Czerwień marki (`bg-brand`, `text-brand`) tylko dla znaku firmowego i aktywnej pozycji w menu, nigdy dla przycisków (czerwony = błąd lub usuwanie).
  - Wszystkie żądania idą przez `lib/api.ts`, który obsługuje CSRF i zamienia ProblemDetail na `ApiError`. Błędy pól mapujemy przez `applyServerErrors`.

## Uwierzytelnianie i uprawnienia

- Bezstanowo: JWT (HS256, `JwtService`) w ciasteczku `AUTH_TOKEN` (HttpOnly, SameSite=Lax), bez sesji HTTP. Do tego CSRF w trybie SPA (`XSRF-TOKEN` → nagłówek `X-XSRF-TOKEN`), bo ciasteczko z tokenem przeglądarka wysyła sama. Bez CORS, bo front i API stoją na jednym originie.
- Klucz podpisu: `JWT_SECRET` (min. 32 znaki). Bez niego klucz losowany jest przy starcie, więc restart backendu wylogowuje wszystkich. Ważność tokenu: `AUTH_TOKEN_TTL` (domyślnie 8h), na HTTPS ustaw `AUTH_COOKIE_SECURE=true`.
- Logowanie: `POST /api/auth/login` (JSON `{username, password}`, ustawia ciasteczko), wylogowanie: `POST /api/auth/logout` (czyści ciasteczko), bieżący użytkownik: `GET /api/auth/me`.
- Rola **Właściciel** jest systemowa (`roles.owner = true`): ma wszystkie uprawnienia, nie da się jej edytować ani usunąć, a w systemie musi zostać co najmniej jeden aktywny właściciel. Pozostałe role tworzy właściciel w Ustawieniach.
- Uprawnienia działają per kategoria narzędzi (`PermissionCategory`) z poziomem `VIEW` lub `EDIT` (EDIT obejmuje VIEW). Authorities Spring Security: `ROLE_OWNER` oraz `KATEGORIA:POZIOM`, np. `SALES:VIEW`.
- `JwtAuthenticationFilter` (własny filtr, celowo nie `oauth2ResourceServer`, bo ten wyłącza CSRF dla żądań z tokenem) przy każdym żądaniu wczytuje użytkownika z tokenu z bazy, więc zmiany ról działają od razu. Token niesie `session_version` (claim `sv`): blokada konta albo zmiana hasła unieważnia wcześniej wydane tokeny.
- `/api/settings/**` jest dostępne tylko dla właściciela.
- Nowych pracowników się zaprasza: właściciel podaje e-mail, konto powstaje bez hasła, a `ActivationService` wysyła jednorazowy link `/aktywacja?token=…` (ważny `ACTIVATION_LINK_TTL`, domyślnie 72h, w bazie tylko SHA-256). Na razie `LoggingAccountMailer` wypisuje maila w logach (`[MAIL] …`). System maili podpinamy jako inną implementację `AccountMailer`.
- `password_change_required`: nowe konto, hasło tymczasowe od właściciela albo domyślne hasło bootstrapu. Taki użytkownik ma tylko authority `PASSWORD_SETUP_REQUIRED` i dostęp wyłącznie do `/api/auth/me` i `POST /api/auth/password/setup`. Frontend przekierowuje go na `/ustaw-haslo`.

### Nowy moduł / kategoria narzędzi

1. Dodaj stałą w `backend/.../permission/PermissionCategory.java`. Nazwa stałej ląduje w bazie, więc istniejących nie zmieniamy.
2. Dodaj ścieżkę i ikonę w `frontend/src/features/tools/categories.ts` (`TOOL_ROUTES`) oraz typ w `lib/types.ts`.
3. Zabezpiecz endpointy: `@PreAuthorize("hasAuthority('SALES:VIEW')")` dla odczytu, `'SALES:EDIT'` dla zmian. We froncie używaj `useCan(category, level)` / `<RequireAuth permission={...}>`.

## Konwencje

- Komunikaty dla użytkownika (walidacja, błędy, UI) piszemy po polsku, kod i identyfikatory po angielsku.
- Ścieżki we froncie po polsku (`/ustawienia/role`, `/sprzedaz`), API po angielsku (`/api/settings/roles`).

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- When graphify-out/graph.json exists, run `graphify query "<question>"` before your first grep, search or source-file read for a codebase question, and put this rule in every subagent prompt that explores code. Read raw files only for lines the graph or an earlier read already located. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- A committed graphify-out/graph.json is rewritten whole on every build, so a large diff to it in a pull request is expected. Do not flag it as churn or suggest skipping the graph rebuild or its hooks.
