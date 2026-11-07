-- Kolejność kategorii w menu bocznym, wspólna dla całego sklepu. group_order to klucze grup z frontu
-- (components/layout/navigation.tsx); pusta lista = kolejność domyślna.
create table store_sidebar_settings (
    id          uuid primary key default gen_random_uuid(),
    store_id    uuid        not null references stores (id),
    group_order text[]      not null default '{}',
    updated_by  uuid references users (id) on delete set null,
    updated_at  timestamptz not null default now(),
    constraint store_sidebar_settings_store_uq unique (store_id)
);
