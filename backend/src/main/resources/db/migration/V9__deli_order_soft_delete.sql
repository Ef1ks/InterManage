-- Usunięcie zamówienia niczego nie kasuje: ukrywa je tylko po stronie, która je usunęła (zamawiający albo
-- dostawca), a druga strona widzi je ze statusem "usunięte". Wyczyszczenie znacznika przywraca zamówienie.
alter table deli_orders
    add column store_deleted_at      timestamptz,
    add column store_deleted_by      uuid   references users (id) on delete set null,
    add column form_store_deleted_at timestamptz,
    add column form_store_deleted_by uuid   references users (id) on delete set null;
