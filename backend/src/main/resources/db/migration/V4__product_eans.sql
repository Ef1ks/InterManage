-- Pary ITM8 + EAN. Raporty sprzedaży zapisują ilości pod konkretną parą, a pary zmieniają się w czasie:
-- ten sam ITM8 dostaje nowy EAN albo EAN przechodzi do nowego ITM8. Nic tu nie jest nadpisywane ani usuwane,
-- więc sprzedaż pod starą parą zostaje przypisana do właściwego artykułu. products.ean to bieżący EAN produktu.
create table product_eans (
    id                   uuid primary key default gen_random_uuid(),
    product_id           uuid        not null references products (id),
    ean                  varchar(14) not null,
    first_seen_import_id uuid        not null references product_imports (id),
    last_seen_import_id  uuid        not null references product_imports (id),
    created_at           timestamptz not null default now()
);

create unique index product_eans_product_ean_uq on product_eans (product_id, ean);
create index product_eans_ean_idx on product_eans (ean);

insert into product_eans (product_id, ean, first_seen_import_id, last_seen_import_id)
select id, ean, first_seen_import_id, last_seen_import_id from products;

-- Zmiany przypisań wykryte przy imporcie, żeby pracownik wiedział, gdzie wymienić etykiety.
-- previous_ean: poprzedni EAN tego produktu (zmiana EAN pod tym samym ITM8).
-- previous_product_id: produkt (inny ITM8), do którego ten EAN należał wcześniej.
create table product_ean_changes (
    id                  uuid primary key default gen_random_uuid(),
    import_id           uuid        not null references product_imports (id),
    product_id          uuid        not null references products (id),
    ean                 varchar(14) not null,
    previous_ean        varchar(14),
    previous_product_id uuid        references products (id),
    check (previous_ean is not null or previous_product_id is not null)
);

create index product_ean_changes_import_id_idx on product_ean_changes (import_id);
create index product_ean_changes_product_id_idx on product_ean_changes (product_id);

alter table product_imports add column ean_changed_count integer not null default 0;
alter table product_imports add column ean_moved_count integer not null default 0;
