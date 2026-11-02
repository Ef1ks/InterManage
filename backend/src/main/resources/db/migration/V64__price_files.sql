-- Ceny z plików TRACEART_RRRRMMDD_HHMMSS_....Traite0, które system sklepu zapisuje w ciągu dnia przy każdej zmianie
-- ceny na kasie. Przeglądarka co kilka minut wysyła nowe pliki z wybranego folderu; cena z pliku nowszego niż raport
-- kartoteki nadpisuje cenę sprzedaży artykułu, a raport kartoteki nadpisze ją dopiero, gdy sam będzie nowszy.
create table price_file_imports (
    id             uuid primary key default gen_random_uuid(),
    store_id       uuid         not null references stores (id),
    file_name      varchar(255) not null,
    -- Czas z nazwy pliku (czas lokalny sklepu, jak report_generated_at raportów).
    file_time      timestamp    not null,
    imported_by    uuid references users (id) on delete set null,
    imported_at    timestamptz  not null default now(),
    total_lines    integer      not null,
    article_count  integer      not null default 0,
    -- Artykuły, którym plik zmienił cenę albo promocję.
    updated_count  integer      not null default 0,
    -- Artykuły z nowszą ceną (z raportu albo późniejszego pliku): plik ich nie ruszył.
    older_count    integer      not null default 0,
    -- ITM8 spoza kartoteki sklepu.
    unknown_count  integer      not null default 0,
    constraint price_file_imports_uq unique (store_id, file_name)
);

create index price_file_imports_store_idx on price_file_imports (store_id, file_time desc);

-- sale_price_at: czas pliku TRACEART, z którego jest bieżąca cena sprzedaży (pusty = cena z raportu kartoteki).
-- Promocja z pliku: cena promocyjna brutto i okres; sale_price to wtedy cena regularna.
alter table products
    add column sale_price_at      timestamp,
    add column promo_price        numeric(12, 2),
    add column promo_from         date,
    add column promo_to           date,
    add column price_file_id      uuid references price_file_imports (id);
