-- Drukarki Bluetooth sklepu (Zebra QLn220, QLn320 i inne): adres MAC wpisuje się raz w Intermanage, kolektory dostają
-- listę na podstawce, a pracownik wybiera na kolektorze drukarkę, z którą idzie pracować. PIN tylko dla drukarek, które
-- wymagają go przy łączeniu (starsze parowanie Bluetooth). Usunięcie to znacznik.
create table store_printers (
    id         uuid primary key,
    store_id   uuid         not null references stores (id),
    name       varchar(60)  not null,
    -- 12 znaków szesnastkowych wielkimi literami, bez dwukropków.
    mac        varchar(12)  not null,
    model      varchar(60),
    pin        varchar(16),
    created_by uuid references users (id) on delete set null,
    created_at timestamptz  not null,
    updated_by uuid references users (id) on delete set null,
    updated_at timestamptz,
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create unique index store_printers_mac on store_printers (store_id, mac) where deleted_at is null;
create index store_printers_store on store_printers (store_id);
