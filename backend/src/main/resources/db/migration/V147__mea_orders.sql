-- Zamówienia MEA zapisane do ATHOS z aplikacji (jak „Wyślij do ATHOS” w eMEA): plik V<nr MEA><pdv 2>.<pdv 3> (rekord
-- 45 znaków na pozycję: nr MEA, ITM8, 4 rozrzuty po 4 cyfry kartonów, 16 zer) spakowany jako R… i zapisany przez
-- przeglądarkę w folderze \\<serwer SR>\appres\comrnis. Wiersz powstaje przy generowaniu pliku (saved_at puste),
-- a saved_at dostaje dopiero po udanym zapisie w folderze; tylko zapisane liczą się jako wysłane. Treść pliku zostaje
-- jako historia, kolejne wysłanie tego samego MEA to kolejny wiersz.
create table mea_orders (
    id         uuid primary key default gen_random_uuid(),
    store_id   uuid         not null references stores (id),
    number     varchar(10)  not null,
    file_name  varchar(40)  not null,
    content    text         not null,
    positions  integer      not null,
    colis      integer      not null,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz  not null default now(),
    saved_at   timestamptz
);

create index mea_orders_store_number on mea_orders (store_id, number, saved_at desc) where saved_at is not null;
