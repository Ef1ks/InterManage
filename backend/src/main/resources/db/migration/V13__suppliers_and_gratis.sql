-- Dostawcy sklepu: z raportu "Lista dostawców" systemu sklepu (kluczem jest "Kod dost.", ten sam co Code fournisseur
-- w liniach dokumentów) i dodani ręcznie w aplikacji (bez kodu, są tylko u nas). Dostawców, których zabrakło
-- w nowym raporcie, nie usuwamy - last_import_id wskazuje ostatni import, w którym byli.

create table supplier_imports (
    id                  uuid primary key default gen_random_uuid(),
    store_id            uuid         not null references stores (id),
    file_name           varchar(255) not null,
    -- "Data : 25/10/2026  12:46:21" z nagłówka raportu.
    report_generated_at timestamp,
    imported_by         uuid         references users (id) on delete set null,
    imported_at         timestamptz  not null default now(),
    total_rows          integer      not null,
    created_count       integer      not null, -- dostawcy, których wcześniej nie było
    updated_count       integer      not null, -- znani, ze zmienionymi danymi
    missing_count       integer      not null  -- znani z wcześniejszych raportów, nieobecni w tym pliku
);

create index supplier_imports_store_idx on supplier_imports (store_id, imported_at desc);

create table suppliers (
    id             uuid primary key default gen_random_uuid(),
    store_id       uuid         not null references stores (id),
    code           varchar(10),           -- Kod dost.; pusty u dostawcy dodanego ręcznie
    keyword        varchar(30),           -- Słowo klucz
    name           varchar(200) not null, -- Nazwa firmy 1 + Nazwa firmy 2
    nip            varchar(20),           -- Kod NIP
    address        varchar(255),          -- Adres 1 + Adres 2
    postal_code    varchar(10),           -- Kod pocztowy
    city           varchar(100),          -- Adres Dostawcy (miejscowość)
    origin         varchar(30),           -- Pochodz. (Bezpośr., Baza)
    last_import_id uuid         references supplier_imports (id),
    created_by     uuid         references users (id) on delete set null,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);

create unique index suppliers_store_code_uq on suppliers (store_id, code) where code is not null;
-- Dwóch ręcznie dodanych dostawców o tej samej nazwie w jednym sklepie to pomyłka.
create unique index suppliers_store_manual_name_uq on suppliers (store_id, lower(name)) where code is null;

-- Dokument zarejestrowany jako gratis od dostawcy: wartością gratisu jest sprzedaż brutto dokumentu
-- (documents.sale_value_gross), więc kolejne importy, które poprawiają linie, poprawiają też gratis.
-- Cofnięcie niczego nie kasuje (deleted_at / deleted_by), zmiana dostawcy to cofnięcie starego wpisu i nowy wpis.
create table document_gratis (
    id            uuid primary key default gen_random_uuid(),
    document_id   uuid        not null references documents (id),
    supplier_id   uuid        not null references suppliers (id),
    registered_by uuid        references users (id) on delete set null,
    registered_at timestamptz not null default now(),
    deleted_by    uuid        references users (id) on delete set null,
    deleted_at    timestamptz
);

create unique index document_gratis_active_uq on document_gratis (document_id) where deleted_at is null;
create index document_gratis_supplier_idx on document_gratis (supplier_id) where deleted_at is null;

-- Dokument z pozycją o nazwie "GRATIS..." (np. "GRATIS SPOŻYWKA MIX" w przeniesieniach stanów) może być gratisem;
-- lista pokazuje wtedy podpowiedź. Liczone przy każdym imporcie z wszystkich linii.
alter table documents add column has_gratis_line boolean not null default false;
update documents d set has_gratis_line = true
where exists (select 1 from document_lines l where l.document_id = d.id and upper(l.name) like '%GRATIS%');
