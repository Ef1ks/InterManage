-- Stała cena sprzedaży dostawcy (Ustawienia sklepu): wszystkie artykuły od tego dostawcy sklep sprzedaje za tę cenę
-- brutto. We wprowadzaniu danych jest ceną sugerowaną artykułów (nowych i z kartoteki) dokumentu tego dostawcy
-- i wpisuje się sama jako cena sprzedaży przy wczytaniu faktury. Zmiana i usunięcie to znacznik deleted_at/by
-- na poprzednim wpisie, aktywny jest najwyżej jeden na dostawcę.
create table supplier_sale_prices (
    id          uuid primary key default gen_random_uuid(),
    store_id    uuid           not null references stores (id),
    supplier_id uuid           not null references suppliers (id),
    sale_price  numeric(10, 2) not null,
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz    not null default now(),
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz,
    constraint supplier_sale_prices_price check (sale_price > 0)
);

create unique index supplier_sale_prices_active on supplier_sale_prices (supplier_id) where deleted_at is null;
create index supplier_sale_prices_store on supplier_sale_prices (store_id) where deleted_at is null;
