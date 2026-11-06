-- Wyroby sklepu zaprzyjaźnionego przypisane do artykułów własnej kartoteki: sklepy mają inne kody EAN i ITM8, a chcą
-- wyeksportować ceny z kalkulacji sąsiada ze swoimi kodami. Odpięcie to znacznik; rejected = ktoś odpiął, więc
-- automatyczne przypisanie nie proponuje tej pary drugi raz.
create table price_calculation_links (
    id                uuid primary key,
    store_id          uuid        not null references stores (id),
    source_product_id uuid        not null references products (id),
    product_id        uuid        not null references products (id),
    auto              boolean     not null default false,
    rejected          boolean     not null default false,
    created_by        uuid references users (id),
    created_at        timestamptz not null,
    deleted_by        uuid references users (id),
    deleted_at        timestamptz
);

create unique index price_calculation_links_source_uq on price_calculation_links (store_id, source_product_id)
    where deleted_at is null;
create index price_calculation_links_store_idx on price_calculation_links (store_id);
