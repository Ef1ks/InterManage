-- Artykuł sprawdzony pod kątem za niskiej marży (raport „Artykuły z za niską marżą”). Weryfikacja obowiązuje dla cen
-- z chwili sprawdzenia: gdy zmieni się cena sprzedaży albo zakupu albo przyjdzie kolejna dostawa (last_purchase_date),
-- artykuł wraca do kontroli. Cofnięcie to znacznik
-- (cancelled_at/by), wpis zostaje.
create table article_margin_verifications (
    id             uuid primary key,
    store_id       uuid           not null references stores (id),
    itm8           varchar(13)    not null,
    sale_price     numeric(12, 2) not null,
    purchase_price numeric(12, 4) not null,
    margin_percent numeric(7, 2)  not null,
    last_purchase_date timestamp,
    note           varchar(500),
    verified_by    uuid references users (id),
    verified_at    timestamptz    not null,
    cancelled_by   uuid references users (id),
    cancelled_at   timestamptz
);

create unique index article_margin_verifications_active_uq
    on article_margin_verifications (store_id, itm8) where cancelled_at is null;
