-- Lista artykułów obecnych w cadencier (formularzu zamówień do bazy), wgrywana w Ustawieniach sklepu z raportu
-- "OBECNE W CADENCIER" (ITM, EAN). Artykuł z tej listy kupiony u innego dostawcy niż baza trafia do raportu
-- weryfikacji cen.
create table cadencier_imports (
    id                  uuid primary key default gen_random_uuid(),
    store_id            uuid         not null references stores (id),
    file_name           varchar(255) not null,
    report_generated_at timestamp,
    imported_by         uuid references users (id) on delete set null,
    imported_at         timestamptz  not null default now(),
    total_rows          integer      not null,
    added_count         integer      not null default 0,
    removed_count       integer      not null default 0
);

create index cadencier_imports_store_idx on cadencier_imports (store_id, imported_at desc);

-- Artykuły obecne teraz w cadencier sklepu. Lista z pliku jest jedynym źródłem prawdy: każdy import zastępuje ją
-- w całości, kody, których nie ma w nowym pliku, są usuwane (historia zostaje tylko w liczbach z cadencier_imports).
create table cadencier_items (
    id        uuid primary key default gen_random_uuid(),
    store_id  uuid        not null references stores (id),
    itm8      varchar(13) not null,
    ean       varchar(14),
    import_id uuid        not null references cadencier_imports (id),
    constraint cadencier_items_uq unique (store_id, itm8)
);
