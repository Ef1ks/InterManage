-- Zamówienia garmażu: status READ (dostawca otworzył nowe zamówienie), zmiana daty dostawy i łączenie zamówień
-- jednego sklepu na ten sam dzień. Nic nie jest kasowane: zamówienia źródłowe zostają i wracają po rozdzieleniu.
alter table deli_orders
    add column read_at                  timestamptz,
    add column read_by                  uuid references users (id) on delete set null,
    -- Data dostawy z chwili złożenia; delivery_date to data obowiązująca po ewentualnej zmianie.
    add column ordered_delivery_date    date,
    add column delivery_date_changed_at timestamptz,
    add column delivery_date_changed_by uuid references users (id) on delete set null,
    -- Zamówienie powstałe z połączenia kilku innych.
    add column merged_at                timestamptz,
    add column merged_by                uuid references users (id) on delete set null,
    -- Zamówienie źródłowe, które weszło do połączonego; znika z list, dopóki połączenie trwa.
    add column merged_into_id           uuid references deli_orders (id),
    -- Połączenie cofnięte: połączone zamówienie znika z list, źródłowe wracają.
    add column split_at                 timestamptz,
    add column split_by                 uuid references users (id) on delete set null;

update deli_orders set ordered_delivery_date = delivery_date;
alter table deli_orders alter column ordered_delivery_date set not null;

create index deli_orders_merged_into_idx on deli_orders (merged_into_id) where merged_into_id is not null;
