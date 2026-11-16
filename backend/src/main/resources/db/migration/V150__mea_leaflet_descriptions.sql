-- Opis gazetki z centrali (np. „Gazetka nr 50_2026 BOŻE NARODZENIE 3.xlsx”): pełny opis promocji gazetkowych dla
-- sieci, więc wspólny dla wszystkich sklepów jak formularze MEA. Pozycję z formularzem MEA łączy ITM 8 PROMO
-- (= mea_document_lines.mea_itm8). Do formularza dokłada formę promocji, megapromocję, limit i cenę dla klienta.
-- Ponowne wgranie gazetki o tym samym roku i numerze (aktualizacja z centrali) daje poprzedniej znacznik replaced_at.
-- Opisy pobiera w tle zadanie MEA z portalu (/api/cad/, lista gazetek z plikami): source_path i source_updated_at to
-- ścieżka i data zmiany pliku w portalu, po nich wiemy, że ten plik już mamy (także zastąpiony). Wgrany ręcznie plik
-- (zapas, gdy portal nie działa) ma je puste i uploaded_by.
create table mea_leaflet_imports (
    id                uuid primary key default gen_random_uuid(),
    year              smallint     not null,
    week              smallint     not null,
    file_name         varchar(300) not null,
    line_count        integer      not null default 0,
    source_path       varchar(500),
    source_updated_at varchar(40),
    uploaded_by       uuid references users (id),
    uploaded_at       timestamptz  not null default now(),
    replaced_by       uuid references users (id),
    replaced_at       timestamptz
);

create unique index mea_leaflet_imports_active on mea_leaflet_imports (year, week) where replaced_at is null;
create index mea_leaflet_imports_source on mea_leaflet_imports (source_path, source_updated_at);

-- Wiersz działu („PRODUKTY SUCHE”) to section kolejnych pozycji. promo_form: oznaczenia z komórki FORMA PROMOCJI,
-- każde w osobnej linii (jak w pliku). Ceny brutto z pliku; purchase_price = CENA CESJI DO ROZLICZEŃ (netto z bazy).
create table mea_leaflet_lines (
    id              uuid primary key default gen_random_uuid(),
    import_id       uuid          not null references mea_leaflet_imports (id),
    lp              integer       not null,
    section         varchar(200),
    date_from       date          not null,
    date_to         date          not null,
    mea_number      varchar(20),
    itm8            varchar(13),
    promo_itm8      varchar(13),
    name            varchar(500)  not null,
    brand           varchar(200),
    promo_form      text,
    mega_kind       varchar(100),
    mega_variant    varchar(100),
    mega_quantity   integer,
    mega_price      numeric(12, 2),
    purchase_price  numeric(12, 2),
    client_price    numeric(12, 2),
    client_limit    integer,
    delivery_form   varchar(50)
);

create index mea_leaflet_lines_import on mea_leaflet_lines (import_id, lp);
create index mea_leaflet_lines_promo_itm8 on mea_leaflet_lines (promo_itm8);
