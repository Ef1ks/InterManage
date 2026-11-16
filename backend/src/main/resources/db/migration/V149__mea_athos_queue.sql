-- Kolejka zamówień MEA do ATHOS: pracownik robi zamówienie u siebie (bez dostępu do folderu comrnis), plik czeka w bazie
-- (queued_at), a na komputerze z dostępem do ATHOS „Wyślij wszystkie” zapisuje pliki sklepu w folderze i każdy dostaje
-- saved_at. Kolejny plik tego samego MEA w kolejce zastępuje poprzedni (replaced_by_id), usunięcie z kolejki to znacznik
-- (removed_at, removed_by) do przywrócenia. Wpisy bez queued_at to zapis od razu do folderu, jak dotąd.
alter table mea_orders
    add column queued_at      timestamptz,
    add column removed_at     timestamptz,
    add column removed_by     uuid references users (id) on delete set null,
    add column replaced_by_id uuid references mea_orders (id);

create index mea_orders_queue on mea_orders (store_id, queued_at)
    where queued_at is not null and saved_at is null and removed_at is null and replaced_by_id is null;
