-- Formularz zamówienia garmażu, osobny dla każdego sklepu: działy (zakładki, np. Rzeźnia, Garmaż, Inne),
-- w nich kategorie, a w kategoriach produkty z kartoteki sklepu. Układa go osoba z uprawnieniem DELI_ORDERS:EDIT.
create table order_sections (
    id         uuid primary key default gen_random_uuid(),
    store_id   uuid         not null references stores (id),
    name       varchar(100) not null,
    position   integer      not null,
    created_at timestamptz  not null default now()
);

create index order_sections_store_id_idx on order_sections (store_id, position);

create table order_categories (
    id         uuid primary key default gen_random_uuid(),
    section_id uuid         not null references order_sections (id) on delete cascade,
    name       varchar(100) not null,
    position   integer      not null,
    created_at timestamptz  not null default now()
);

create index order_categories_section_id_idx on order_categories (section_id, position);

create table order_category_products (
    id          uuid primary key default gen_random_uuid(),
    category_id uuid        not null references order_categories (id) on delete cascade,
    product_id  uuid        not null references products (id),
    position    integer     not null,
    created_at  timestamptz not null default now()
);

create unique index order_category_products_uq on order_category_products (category_id, product_id);
create index order_category_products_product_id_idx on order_category_products (product_id);
