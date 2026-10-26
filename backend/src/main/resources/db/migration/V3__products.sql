-- Codzienny import kartoteki produktów z raportu Excel (arkusz "Mercalys", nagłówek EAN | NAZWA | ... | CN).
-- Każde wgranie pliku to jeden wiersz tutaj; nieudane importy są wycofywane w całości i nie zostawiają śladu.
create table product_imports (
    id                  uuid primary key default gen_random_uuid(),
    file_name           varchar(255) not null,
    -- "Data : 24/10/2026 16:04:43" z nagłówka raportu, czyli kiedy system sklepu wygenerował plik.
    report_generated_at timestamp,
    imported_by         uuid         references users (id) on delete set null,
    imported_at         timestamptz  not null default now(),
    total_rows          integer      not null,
    created_count       integer      not null,
    updated_count       integer      not null,
    unchanged_count     integer      not null,
    -- Produkty z bazy, których nie było w tym pliku (zostają w bazie, mają starsze last_seen_import_id).
    missing_count       integer      not null
);

create index product_imports_imported_at_idx on product_imports (imported_at desc);

-- Aktualny stan produktu według ostatniego importu. Kluczem jest ITM8 (numer artykułu Intermarché),
-- bo EAN produktu może się zmienić. Nazwy kolumn z pliku w komentarzach.
create table products (
    id                   uuid primary key default gen_random_uuid(),
    itm8                 varchar(13)    not null, -- ITM8
    ean                  varchar(14)    not null, -- EAN (priorytetowy dla ITM8)
    name                 varchar(255),            -- NAZWA
    brand                varchar(100),            -- MARKA
    supplier             varchar(255),            -- DOST
    nomenclature         varchar(20),             -- NOM, np. "01 01 01" (dział, grupa, podgrupa)
    sale_price           numeric(12, 2),          -- CENA_SPRZEDAZY (brutto)
    purchase_price       numeric(12, 4),          -- CENA_ZAKUPU
    stock                numeric(14, 3),          -- STAN, może być ujemny
    rotation             numeric(14, 3),          -- ROTACJA
    case_size            numeric(12, 3),          -- COLI (sztuk w kartonie)
    active               boolean,                 -- AKTYWNY (Tak/Nie)
    priority_ean         boolean,                 -- PRIO (Tak/Nie)
    vat_code             smallint,                -- VAT (kod stawki z systemu sklepu, nie procent)
    unit                 varchar(10),             -- JED, jednostka ceny jednostkowej (szt., kg, L...)
    net_content          numeric(12, 3),          -- WAGA, zawartość w jednostce JED
    dlv                  integer,                 -- DLV
    rp                   smallint,                -- RP
    deposit_code         smallint,                -- KAUCJA
    reference            varchar(20),             -- REFERENCJA
    till_code            smallint,                -- KASA
    type_code            smallint,                -- TYP
    kind                 varchar(30),             -- RODZ (Inne, PNM...)
    sale_unit_code       smallint,                -- JMSP
    cn_code              varchar(30),             -- CN, kod celny
    first_seen_import_id uuid           not null references product_imports (id),
    last_seen_import_id  uuid           not null references product_imports (id),
    created_at           timestamptz    not null default now(),
    -- Ostatnia zmiana któregokolwiek pola z pliku (nie samo ponowne wgranie tych samych danych).
    updated_at           timestamptz    not null default now()
);

create unique index products_itm8_uq on products (itm8);
create index products_ean_idx on products (ean);
create index products_last_seen_import_id_idx on products (last_seen_import_id);
