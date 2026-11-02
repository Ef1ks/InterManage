-- Promocje z raportu "LISTA BIEŻĄCYCH I PRZYSZŁYCH PROMOCJI" (Taryfy.pdf): bieżące i przyszłe promocje artykułów
-- z cenami. Na razie tylko zapisywane, raporty skorzystają z nich później.
create table promotion_imports (
    id                  uuid primary key default gen_random_uuid(),
    store_id            uuid         not null references stores (id),
    file_name           varchar(255) not null,
    report_generated_at timestamp,
    -- Zakres dat końca promocji, który objął raport ("DATY KOŃCA 02/11/26 - 02/11/28").
    end_from            date,
    end_to              date,
    imported_by         uuid references users (id) on delete set null,
    imported_at         timestamptz  not null default now(),
    total_rows          integer      not null,
    created_count       integer      not null default 0,
    updated_count       integer      not null default 0,
    missing_count       integer      not null default 0
);

create index promotion_imports_store_idx on promotion_imports (store_id, imported_at desc);

-- Promocja = artykuł (ITM8) w okresie od-do. Ponowny import nadpisuje ceny i nazwę, a promocja, której zabrakło
-- w nowszym raporcie (np. odwołana), zostaje: last_import_id wskazuje ostatni raport, w którym była.
create table promotions (
    id                     uuid primary key default gen_random_uuid(),
    store_id               uuid          not null references stores (id),
    itm8                   varchar(13)   not null,
    ean                    varchar(14),
    name                   varchar(100)  not null,
    start_date             date          not null,
    end_date               date          not null,
    -- "Cena Zak. Katalog.": cena zakupu netto z katalogu.
    catalog_purchase_price numeric(12, 2),
    -- "Ceny Sprzed. Przegląd": zwykła cena sprzedaży brutto.
    regular_sale_price     numeric(12, 2),
    promo_price            numeric(12, 2),
    -- "Marża" z raportu, w procentach.
    margin_percent         numeric(10, 2),
    first_import_id        uuid          not null references promotion_imports (id),
    last_import_id         uuid          not null references promotion_imports (id),
    constraint promotions_uq unique (store_id, itm8, start_date, end_date)
);

create index promotions_store_end_idx on promotions (store_id, end_date);
create index promotions_last_import_idx on promotions (last_import_id);

-- Multikody z raportu "Szczegóły informacji kodu sprzedaży" (Multikody.xlsx): wszystkie EAN-y przypisane w danej
-- chwili do każdego ITM8, z kodem głównym (Prio), jednostką, opakowaniem, współczynnikiem i ceną sprzedaży.
create table product_code_imports (
    id                  uuid primary key default gen_random_uuid(),
    store_id            uuid         not null references stores (id),
    file_name           varchar(255) not null,
    report_generated_at timestamp,
    imported_by         uuid references users (id) on delete set null,
    imported_at         timestamptz  not null default now(),
    total_rows          integer      not null,
    created_count       integer      not null default 0,
    updated_count       integer      not null default 0,
    missing_count       integer      not null default 0
);

create index product_code_imports_store_idx on product_code_imports (store_id, imported_at desc);

-- Para ITM8 + EAN. Obowiązujące teraz (current) to te z najnowszego importu; para, której zabrakło w nowszym pliku,
-- zostaje jako historia przypisań z current = false. Wyszukiwanie artykułów po EAN-ie bierze wszystkie obowiązujące
-- kody, nie tylko główny.
create table product_codes (
    id              uuid primary key default gen_random_uuid(),
    store_id        uuid          not null references stores (id),
    itm8            varchar(13)   not null,
    ean             varchar(14)   not null,
    name            varchar(100),
    -- Kod główny artykułu ("Prio" = X).
    prio            boolean       not null,
    sale_unit       varchar(30),
    sale_pack       numeric(12, 3),
    -- "Współcz. ceny": cena kodu = współczynnik × cena artykułu (np. kody z etykiet obniżek 0,707).
    price_factor    numeric(10, 3),
    sale_price      numeric(12, 2),
    -- "Data usunięcia": kiedy system sklepu usunie kod.
    removal_date    date,
    deposit_code    smallint,
    current         boolean       not null default true,
    first_import_id uuid          not null references product_code_imports (id),
    last_import_id  uuid          not null references product_code_imports (id),
    constraint product_codes_uq unique (store_id, itm8, ean)
);

-- Początek EAN-u (wyszukiwarki) i EAN bez zer wiodących (skaner, pozycje faktur).
create index product_codes_current_ean_idx on product_codes (store_id, ean varchar_pattern_ops) where current;
create index product_codes_current_ean_key_idx on product_codes (store_id, ltrim(ean, '0')) where current;
create index product_codes_current_itm8_idx on product_codes (store_id, itm8) where current;
create index product_codes_last_import_idx on product_codes (last_import_id);
