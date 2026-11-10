-- Progi raportu marż poza normą, osobno w każdym sklepie: marża niższa od wymaganej o low_gap punktów procentowych
-- (i więcej) jest za niska, wyższa o high_gap (i więcej) za wysoka. Brak wiersza = progi domyślne (5 i 10).
create table low_margin_settings (
    id         uuid primary key default gen_random_uuid(),
    store_id   uuid          not null references stores (id),
    low_gap    numeric(5, 2) not null,
    high_gap   numeric(5, 2) not null,
    updated_by uuid references users (id) on delete set null,
    updated_at timestamptz   not null default now(),
    constraint low_margin_settings_store_uq unique (store_id),
    constraint low_margin_settings_low check (low_gap between 0 and 100),
    constraint low_margin_settings_high check (high_gap between 0 and 100)
);
