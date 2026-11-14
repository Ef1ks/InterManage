-- Raport kasowy sklepu (jedna kasa na sklep). Każdy wpływ to dokument KP (dowód wpłaty), każda wypłata KW (dowód
-- wypłaty). Dokumentów się nie usuwa ani nie zmienia: pomyłkę anuluje się (znacznik z powodem, numer zostaje
-- w historii, żeby numeracja była ciągła) i wystawia nowy dokument.
-- Pierwsza wersja, w tej postaci zastosowana na produkcji; zmiany są w V137.

-- Skan pieczątki sklepu (PNG albo JPG) drukowany na KP, KW i raporcie. Aktywna jedna na sklep; wymiana i usunięcie
-- to znacznik na poprzedniej. Dokument zapamiętuje pieczątkę z chwili wystawienia, więc ponowny wydruk jest taki sam.
create table cash_stamps (
    id           uuid primary key,
    store_id     uuid         not null references stores (id),
    file_name    varchar(255) not null,
    content_type varchar(100) not null,
    size_bytes   integer      not null,
    content      bytea        not null,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz  not null,
    removed_by   uuid references users (id) on delete set null,
    removed_at   timestamptz
);

create index cash_stamps_store_idx on cash_stamps (store_id);
create unique index cash_stamps_active_uq on cash_stamps (store_id) where removed_at is null;

-- Stan kasy na początek dnia, od którego sklep prowadzi raport w aplikacji. Dokumenty nie mogą mieć wcześniejszej
-- daty. Zmiana to znacznik na poprzednim wpisie i nowy wpis.
create table cash_opening_balances (
    id           uuid primary key,
    store_id     uuid           not null references stores (id),
    balance_date date           not null,
    amount       numeric(12, 2) not null check (amount >= 0),
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz    not null,
    removed_by   uuid references users (id) on delete set null,
    removed_at   timestamptz
);

create unique index cash_opening_balances_active_uq on cash_opening_balances (store_id) where removed_at is null;

-- Dokumenty kasowe, numer kolejny w roku, osobno dla każdego sklepu.
create table cash_documents (
    id                   uuid primary key,
    store_id             uuid           not null references stores (id),
    kind                 varchar(2)     not null check (kind in ('KP', 'KW')),
    year                 integer        not null,
    sequence_number      integer        not null,
    issue_date           date           not null,
    amount               numeric(12, 2) not null check (amount > 0),
    counterparty         varchar(200)   not null,
    counterparty_address varchar(300),
    title                varchar(500)   not null,
    stamp_id             uuid           not null references cash_stamps (id),
    created_by           uuid references users (id) on delete set null,
    created_at           timestamptz    not null,
    cancelled_by         uuid references users (id) on delete set null,
    cancelled_at         timestamptz,
    cancel_reason        varchar(300)
);

create unique index cash_documents_number_uq on cash_documents (store_id, kind, year, sequence_number);
create index cash_documents_store_date_idx on cash_documents (store_id, issue_date);
