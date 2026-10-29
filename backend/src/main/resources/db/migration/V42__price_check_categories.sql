-- Własne kategorie raportu zakupu bazowego poza bazą (druga linia zakładek pod "Do weryfikacji" i "Po incydencie
-- z bazy"): nazwa i rejony nomenklatury (kod dwucyfrowy). Przyjęcie należy do kategorii, gdy ma artykuł z cadencier
-- z jednego z jej rejonów. Usunięcie to znacznik.
create table price_check_categories (
    id           uuid primary key default gen_random_uuid(),
    store_id     uuid        not null references stores (id),
    name         varchar(80) not null,
    region_codes text[]      not null,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz not null default now(),
    updated_by   uuid references users (id) on delete set null,
    updated_at   timestamptz,
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz
);

create unique index price_check_categories_name_uq on price_check_categories (store_id, lower(name))
    where deleted_at is null;
