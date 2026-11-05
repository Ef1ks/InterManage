-- Promocje z plików TRACEART, także tych starszych niż kartoteka (w products jest tylko bieżąca): ręcznie założonych
-- promocji nie ma na liście promocji, więc to jedyne miejsce, z którego wiadomo, że artykuł był w promocji danego dnia.
create table till_promotions (
    store_id      uuid          not null references stores (id),
    itm8          varchar(13)   not null,
    promo_from    date          not null,
    promo_to      date          not null,
    promo_price   numeric(12, 2) not null,
    regular_price numeric(12, 2) not null,
    -- Czas z nazwy pliku, z którego jest cena regularna.
    file_time     timestamp     not null,
    primary key (store_id, itm8, promo_from, promo_to, promo_price)
);

-- Pliki wczytane przed tą migracją trzeba przeczytać jeszcze raz: przeglądarka wysyła je sama, a serwer bierze z nich
-- tylko promocje, bez zmiany cen.
alter table price_file_imports add column promotions_read boolean not null default false;

-- Dostawcy, których nie dotyczy marża wymagana: ich artykułów nie ma w raporcie za niskich marż. Po kodzie dostawcy
-- z linii dokumentów; nazwa to kopia do wyświetlania. Usunięcie z listy to znacznik.
create table low_margin_excluded_suppliers (
    id            uuid primary key,
    store_id      uuid         not null references stores (id),
    supplier_code varchar(10)  not null,
    supplier_name varchar(200),
    created_by    uuid references users (id),
    created_at    timestamptz  not null,
    removed_by    uuid references users (id),
    removed_at    timestamptz
);

create unique index low_margin_excluded_suppliers_active_idx
    on low_margin_excluded_suppliers (store_id, supplier_code) where removed_at is null;
