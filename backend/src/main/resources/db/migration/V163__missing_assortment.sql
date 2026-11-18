-- Brakujący asortyment: artykuły kupione od dostawców spoza bazy, których nie ma na stanie i które się nie sprzedają.
-- Kategorie (np. „wycofane z produkcji”, „aktualnie brak u dostawcy”) zakłada sklep, a widzą i używają ich też sklepy
-- zaprzyjaźnione. Artykuł do kategorii przypisuje każdy sklep u siebie, po ITM8, bo ITM8 i stany są w każdym sklepie
-- inne. Usunięcie kategorii i zdjęcie przypisania to znacznik.
create table missing_assortment_categories (
    id         uuid primary key,
    store_id   uuid        not null references stores (id),
    name       text        not null,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create unique index missing_assortment_categories_name_uq on missing_assortment_categories (store_id, lower(name))
    where deleted_at is null;

create table missing_assortment_assignments (
    id          uuid primary key,
    store_id    uuid        not null references stores (id),
    itm8        text        not null,
    category_id uuid        not null references missing_assortment_categories (id),
    assigned_by uuid references users (id) on delete set null,
    assigned_at timestamptz not null,
    removed_by  uuid references users (id) on delete set null,
    removed_at  timestamptz
);

create unique index missing_assortment_assignments_uq on missing_assortment_assignments (store_id, itm8)
    where removed_at is null;
