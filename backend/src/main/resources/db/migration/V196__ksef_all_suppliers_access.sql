-- Faktury KSeF: bez tego wpisu konto widzi tylko faktury dostawców z listy dostawców sklepu (wgranej z raportu).
-- Wszystkich dostawców widzą zawsze właściciel, Aderon i ranga Księgowość; innym kontom Aderon włącza to tutaj.
-- Wyłączenie to znacznik.
create table ksef_all_suppliers_access (
    id          uuid primary key,
    store_id    uuid        not null references stores (id),
    user_id     uuid        not null references users (id),
    created_by  uuid references users (id),
    created_at  timestamptz not null,
    removed_by  uuid references users (id),
    removed_at  timestamptz
);

create unique index ksef_all_suppliers_access_active_uq
    on ksef_all_suppliers_access (store_id, user_id) where removed_at is null;
