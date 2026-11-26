-- Lista opakowań zwrotnych z systemu sklepu (PDF "LISTA OPAKOWAŃ ZWROTNYCH WG KODU"): kod kaucji, który mają
-- artykuły w kartotece (products.deposit_code) i kody w multikodach (product_codes.deposit_code), z nazwą
-- i wartością. Z niej kolektor drukuje kaucję na cenówce.
create table deposit_kind_imports (
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

create index deposit_kind_imports_store_idx on deposit_kind_imports (store_id, imported_at desc);

-- Rodzaj opakowania zwrotnego sklepu. Kod, którego zabrakło w nowszej liście, zostaje (last_import_id wskazuje
-- ostatnią listę z nim).
create table deposit_kinds (
    id              uuid primary key default gen_random_uuid(),
    store_id        uuid          not null references stores (id),
    code            smallint      not null,
    name            varchar(100)  not null,
    vat_code        smallint,
    value           numeric(10, 2) not null,
    -- Ostatnia kolumna raportu ("Kaucja"); na razie zawsze to samo.
    kind            varchar(50),
    first_import_id uuid          not null references deposit_kind_imports (id),
    last_import_id  uuid          not null references deposit_kind_imports (id),
    constraint deposit_kinds_uq unique (store_id, code)
);
