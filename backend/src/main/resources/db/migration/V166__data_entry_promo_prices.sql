-- Kolejka cen zwykłych artykułów w trwającej promocji (zmiana cen z pliku ich nie obejmuje, więc wpisuje je makro
-- w taryfie Mercalys od dnia wprowadzenia). Wpisy dodaje zapis pliku XML we wprowadzaniu danych albo „Dodaj z tabeli”;
-- nowy wpis tego samego EAN-u, który jeszcze czeka, daje poprzedniemu deleted_at (najnowsza cena wygrywa).
-- generated_at: poszedł już do makra, deleted_at: usunięty z listy, do przywrócenia.
create table data_entry_promo_prices (
    id           uuid primary key default gen_random_uuid(),
    store_id     uuid           not null references stores (id),
    ean          text           not null,
    itm8         text           not null,
    name         text           not null,
    sale_price   numeric(12, 2) not null,
    vat_code     text           not null,
    promo_price  numeric(12, 2),
    promo_to     date,
    document     text,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz    not null default now(),
    generated_by uuid references users (id) on delete set null,
    generated_at timestamptz,
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz
);

create index data_entry_promo_prices_store_idx on data_entry_promo_prices (store_id, created_at desc)
    where deleted_at is null;
