-- Zaokrąglanie cen sugerowanych we wprowadzaniu danych według przedziałów cen (Ustawienia sklepu): cena z przedziału
-- [price_from, price_to) idzie w górę do najbliższej ceny z końcówką co (ending + 0,01 zł), np. 0,49 = końcówki ,49
-- i ,99, a 0,99 = zawsze ,99. Pusty price_to = bez górnej granicy. Zapis zastępuje całą listę: poprzednie reguły
-- dostają znacznik deleted_at/by (historia zostaje).
create table price_rounding_rules (
    id         uuid primary key default gen_random_uuid(),
    store_id   uuid           not null references stores (id),
    price_from numeric(10, 2) not null,
    price_to   numeric(10, 2),
    ending     numeric(3, 2)  not null,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz    not null default now(),
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz,
    constraint price_rounding_rules_from check (price_from >= 0),
    constraint price_rounding_rules_to check (price_to is null or price_to > price_from),
    constraint price_rounding_rules_ending check (ending > 0 and ending < 1)
);

create index price_rounding_rules_store on price_rounding_rules (store_id) where deleted_at is null;
