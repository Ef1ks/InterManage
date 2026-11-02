-- Instrukcje do raportów w Imporcie danych (okienko pod znakiem zapytania przy tytule raportu): jak wygenerować
-- plik w systemie sklepu i gdzie go szukać. Wspólne dla wszystkich sklepów, pisze je właściciel. Zmiana daje
-- poprzedniemu wpisowi znacznik deleted_at/by, aktywny jest najwyżej jeden na rodzaj raportu.
create table import_guides (
    id          uuid primary key default gen_random_uuid(),
    kind        text        not null,
    content     text        not null,
    folder_path text,
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null default now(),
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz
);

create unique index import_guides_active on import_guides (kind) where deleted_at is null;
