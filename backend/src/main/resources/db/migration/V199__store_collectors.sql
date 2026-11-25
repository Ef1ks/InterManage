-- Kolektory sklepu (Motorola MC3190, Windows CE 6.0, program w collector/): magazyn artykułów bez sieci, dane pobierane
-- na podstawce. Kolektor paruje się jednorazowym kodem z Ustawień sklepu (w bazie SHA-256 kodu, ważny krótko), a potem
-- przedstawia się kluczem (w bazie tylko SHA-256). Nowy kod dla tego samego kolektora (po wyczyszczeniu urządzenia)
-- zostawia wpis, a nowy klucz zastępuje poprzedni przy parowaniu. Usunięcie to znacznik.
create table store_collectors (
    id                uuid primary key,
    store_id          uuid         not null references stores (id),
    name              varchar(100) not null,
    key_hash          varchar(64) unique,
    pair_code_hash    varchar(64),
    pair_expires_at   timestamptz,
    paired_at         timestamptz,
    created_by        uuid references users (id) on delete set null,
    created_at        timestamptz  not null,
    last_seen_at      timestamptz,
    last_version      varchar(50),
    device_name       varchar(100),
    -- Wersja kartoteki, którą kolektor potwierdził po zapisaniu u siebie.
    catalog_version   varchar(64),
    catalog_synced_at timestamptz,
    deleted_by        uuid references users (id) on delete set null,
    deleted_at        timestamptz
);

create index store_collectors_store on store_collectors (store_id) where deleted_at is null;
create unique index store_collectors_pair_code on store_collectors (pair_code_hash) where pair_code_hash is not null;
