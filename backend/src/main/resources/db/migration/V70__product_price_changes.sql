-- Historia cen sprzedaży artykułu w Magazynie i produktach. products trzyma tylko bieżącą cenę, więc każda zmiana
-- ceny sprzedaży albo promocji z pliku TRACEART (kasa) i ceny sprzedaży z importu kartoteki zostawia tu wpis ze stanem
-- przed i po. Zmiany cen z wprowadzania danych są w treści plików CTRLPRIX (data_entry_price_control_files).
create table product_price_changes (
    id                  uuid primary key default gen_random_uuid(),
    store_id            uuid           not null references stores (id),
    product_id          uuid           not null references products (id),
    -- Czas z nazwy pliku TRACEART albo wygenerowania raportu kartoteki (czas lokalny sklepu).
    changed_at          timestamp,
    -- TILL: plik TRACEART, REPORT: import kartoteki.
    source              varchar(10)    not null,
    previous_sale_price numeric(12, 2),
    sale_price          numeric(12, 2),
    previous_promo_price numeric(12, 2),
    promo_price         numeric(12, 2),
    promo_from          date,
    promo_to            date,
    price_file_id       uuid references price_file_imports (id),
    product_import_id   uuid references product_imports (id),
    created_at          timestamptz    not null default now()
);

create index product_price_changes_product_idx on product_price_changes (product_id, changed_at desc);
