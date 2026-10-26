-- Kalkulator cen garmażu: przepis z produktów kartoteki sklepu i ilości każdego z nich. Koszt liczymy zawsze
-- z bieżących cen zakupu w kartotece (products.purchase_price / net_content), więc nie kopiujemy tu cen -
-- każdy import raportu produktów aktualizuje wszystkie kalkulacje.
create table price_calculations (
    id          uuid primary key default gen_random_uuid(),
    store_id    uuid         not null references stores (id),
    name        varchar(150) not null,
    -- PER_KG: ilości składników na 1 kg wyrobu; PER_BATCH: na cały przepis, z którego wychodzi batch_yield kg.
    mode        varchar(10)  not null,
    batch_yield numeric(12, 3),
    created_by  uuid         references users (id) on delete set null,
    created_at  timestamptz  not null default now(),
    updated_by  uuid         references users (id) on delete set null,
    updated_at  timestamptz  not null default now(),
    -- Usunięcie to znacznik, kalkulację da się przywrócić.
    deleted_by  uuid         references users (id) on delete set null,
    deleted_at  timestamptz,
    constraint price_calculations_yield_ck check (mode <> 'PER_BATCH' or batch_yield > 0)
);

create index price_calculations_store_idx on price_calculations (store_id) where deleted_at is null;

-- Składniki w kolejności z kreatora. Ilość w jednostce produktu z kartoteki (kg, L, szt.).
create table price_calculation_items (
    id             uuid primary key default gen_random_uuid(),
    calculation_id uuid           not null references price_calculations (id) on delete cascade,
    product_id     uuid           not null references products (id),
    position       integer        not null,
    quantity       numeric(12, 3) not null check (quantity > 0)
);

create unique index price_calculation_items_product_uq on price_calculation_items (calculation_id, product_id);
create index price_calculation_items_product_idx on price_calculation_items (product_id);
