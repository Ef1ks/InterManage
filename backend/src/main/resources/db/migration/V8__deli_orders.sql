-- Zamawianie z formularza garmażu. Formularz należy do sklepu (dostawcy), który zaprasza inne sklepy;
-- sklep dostawcy też może zamawiać ze swojego formularza.

-- W czym zamawia się produkt: KG (do 3 miejsc po przecinku) albo SZT (całe sztuki). Ustawia to osoba
-- układająca formularz; na start podpowiadamy kg dla produktów, których jednostka w kartotece to kg.
alter table order_category_products add column order_unit varchar(10) not null default 'SZT';
update order_category_products ocp set order_unit = 'KG'
from products p where p.id = ocp.product_id and lower(p.unit) = 'kg';

create table deli_form_invitations (
    id            uuid primary key default gen_random_uuid(),
    -- Sklep, do którego formularza zapraszamy (dostawca).
    form_store_id uuid        not null references stores (id),
    -- Zaproszony sklep (zamawiający).
    store_id      uuid        not null references stores (id),
    status        varchar(20) not null, -- PENDING, ACCEPTED, DECLINED
    invited_by    uuid        references users (id) on delete set null,
    invited_at    timestamptz not null default now(),
    responded_by  uuid        references users (id) on delete set null,
    responded_at  timestamptz,
    check (form_store_id <> store_id)
);

create unique index deli_form_invitations_uq on deli_form_invitations (form_store_id, store_id);
create index deli_form_invitations_store_id_idx on deli_form_invitations (store_id, status);

create table deli_orders (
    id            uuid primary key default gen_random_uuid(),
    form_store_id uuid        not null references stores (id),
    store_id      uuid        not null references stores (id),
    status        varchar(20) not null, -- NEW, FULFILLED
    delivery_date date        not null,
    submitted_by  uuid        references users (id) on delete set null,
    submitted_at  timestamptz not null default now(),
    corrected_by  uuid        references users (id) on delete set null,
    corrected_at  timestamptz,
    fulfilled_by  uuid        references users (id) on delete set null,
    fulfilled_at  timestamptz
);

create index deli_orders_form_store_idx on deli_orders (form_store_id, status, submitted_at desc);
create index deli_orders_store_idx on deli_orders (store_id, submitted_at desc);

-- Pozycje zamówienia. Nazwy działu i kategorii są kopią z chwili złożenia, bo formularz może się potem zmienić.
create table deli_order_items (
    id               uuid primary key default gen_random_uuid(),
    order_id         uuid           not null references deli_orders (id) on delete cascade,
    product_id       uuid           not null references products (id),
    section_name     varchar(100)   not null,
    category_name    varchar(100)   not null,
    unit             varchar(10)    not null,
    -- Ilość wysłana przez sklep i ilość po ewentualnej korekcie dostawcy (0 = nie będzie dostarczone).
    ordered_quantity numeric(10, 3) not null check (ordered_quantity > 0),
    quantity         numeric(10, 3) not null check (quantity >= 0),
    position         integer        not null
);

create index deli_order_items_order_id_idx on deli_order_items (order_id, position);
