-- Wysyłki zamówień MEA z ATHOS odczytane z dziennika ATHOS (\\<serwer SR>\appres\athos2000\Histo.Log), jak przycisk
-- „Sprawdź statusy” w eMEA: data wysyłki, numer MEA, liczba pozycji i kartonów (COLI). Na ich podstawie MEA jest
-- „wysłane”, a nie „do wysłania”. Ten sam wpis z kolejnego odczytu dziennika nie dochodzi drugi raz (unikalność),
-- a MEA wysłane kilka razy (poprawki) ma kilka wpisów; obowiązuje najnowszy.
create table mea_athos_sends (
    id         uuid primary key default gen_random_uuid(),
    store_id   uuid        not null references stores (id),
    number     varchar(10) not null,
    sent_at    timestamp   not null,
    positions  integer     not null,
    colis      integer     not null,
    created_at timestamptz not null default now(),
    constraint mea_athos_sends_uq unique (store_id, number, sent_at)
);

create index mea_athos_sends_store_number on mea_athos_sends (store_id, number, sent_at desc);

-- Każdy odczyt dziennika (z folderu wybranego w przeglądarce): kiedy, kto, ile wysyłek było w pliku i ile nowych.
-- Sklep bez żadnego odczytu liczy zakładki „po staremu” (z flagi zamówienia z portalu).
create table mea_athos_imports (
    id             uuid primary key default gen_random_uuid(),
    store_id       uuid        not null references stores (id),
    file_name      varchar(255) not null,
    file_modified  timestamptz,
    found_count    integer     not null,
    added_count    integer     not null,
    last_sent_at   timestamp,
    imported_by    uuid references users (id) on delete set null,
    imported_at    timestamptz not null default now()
);

create index mea_athos_imports_store on mea_athos_imports (store_id, imported_at desc);
