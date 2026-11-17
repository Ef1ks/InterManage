-- Kolejka plików sklepu: przy pracy dla innego sklepu (bez połączenia zdalnego z jego komputerem) pliki dla programu
-- sklepu (XML faktury, nowe artykuły, protokoły, zmiana cen, etykiety, zamówienia, .PRO, .lst…) zamiast do folderu
-- trafiają tutaj, a pod koniec pracy, na komputerze z dostępem do folderów sklepu, „Zapisz wszystkie” zapisuje je
-- w folderach w kolejności dodania i każdy dostaje saved_at. folder_key to klucz folderu wybranego w przeglądarce
-- (np. deli-order-proto), folder_label jego opis. Usunięcie z kolejki to znacznik (removed_at, removed_by).
create table store_file_queue (
    id           uuid primary key,
    store_id     uuid          not null references stores (id),
    folder_key   varchar(100)  not null,
    folder_label varchar(200)  not null,
    file_name    varchar(255)  not null,
    size_bytes   integer       not null,
    content      bytea         not null,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz   not null,
    saved_by     uuid references users (id) on delete set null,
    saved_at     timestamptz,
    removed_by   uuid references users (id) on delete set null,
    removed_at   timestamptz
);

create index store_file_queue_pending on store_file_queue (store_id, created_at)
    where saved_at is null and removed_at is null;
