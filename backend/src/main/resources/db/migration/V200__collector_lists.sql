-- Listy zeskanowane na kolektorach (Protokoły): kolektor wysyła listę po włożeniu na podstawkę, a w Intermanage robi się
-- z niej protokół (PROTO1-PROTO6), Excel albo wydruk. Id listy nadaje kolektor, więc ponowiona wysyłka niczego nie dubluje.
create table collector_lists (
    id           uuid primary key,
    store_id     uuid        not null references stores (id),
    collector_id uuid        not null references store_collectors (id),
    -- Kiedy zaczęto listę na kolektorze (czas sklepu, zegar kolektora).
    started_at   timestamp,
    received_at  timestamptz not null,
    line_count   integer     not null,
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz
);

create index collector_lists_store on collector_lists (store_id, received_at desc);

-- Pozycja: zeskanowany kod, wpisana ilość i ilość w jednostce głównego kodu (puszka z czteropaka: 8 wpisane = 2).
-- Nazwa i marka są z kartoteki kolektora z chwili skanowania.
create table collector_list_lines (
    id               uuid primary key,
    list_id          uuid           not null references collector_lists (id),
    position         integer        not null,
    itm8             varchar(13)    not null,
    scanned_code     varchar(20)    not null,
    ean              varchar(14)    not null,
    name             varchar(100)   not null,
    brand            varchar(100)   not null,
    entered_quantity numeric(12, 3) not null,
    quantity         numeric(14, 3) not null,
    constraint collector_list_lines_position unique (list_id, position)
);

-- Protokoły zapisane z listy; cofnięcie oznaczenia to znacznik.
create table collector_list_protocols (
    id         uuid primary key,
    list_id    uuid         not null references collector_lists (id),
    kind       varchar(30)  not null,
    file_name  varchar(200) not null,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz  not null,
    undone_by  uuid references users (id) on delete set null,
    undone_at  timestamptz
);

create index collector_list_protocols_list on collector_list_protocols (list_id);
