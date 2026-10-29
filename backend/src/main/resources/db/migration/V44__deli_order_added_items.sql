-- Pozycje dopisane do zamówienia garmażu po jego złożeniu (zamawiający zapomniał o produkcie): kto i kiedy dopisał,
-- żeby dostawca wiedział, że wydruk sprzed tej chwili ich nie ma.
alter table deli_order_items
    add column added_by uuid references users (id) on delete set null,
    add column added_at timestamptz;
