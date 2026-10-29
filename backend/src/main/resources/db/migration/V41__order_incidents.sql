-- Incydenty zamówień do bazy (plik "<nr sklepu>_RRMMDD_..._INCYDENTY.xlsx"): artykuły, które nie przyjechały
-- z bazy, z przyczyną. Data incydentu pochodzi z nazwy pliku. Przyjęcie od innego dostawcy do 10 dni po incydencie
-- trafia w raporcie zakupu bazowego poza bazą do osobnej kategorii, bo baza go wtedy nie dowiozła.
create table incident_imports (
    id            uuid primary key default gen_random_uuid(),
    store_id      uuid         not null references stores (id),
    file_name     varchar(255) not null,
    incident_date date         not null,
    imported_by   uuid references users (id) on delete set null,
    imported_at   timestamptz  not null default now(),
    total_rows    integer      not null,
    added_count   integer      not null default 0
);

create index incident_imports_store_idx on incident_imports (store_id, imported_at desc);

-- Artykuł w incydencie danego dnia. Ponowne wgranie pliku z tego dnia nadpisuje pola (import_id wskazuje ostatni),
-- niczego nie usuwa.
create table order_incidents (
    id            uuid primary key default gen_random_uuid(),
    store_id      uuid        not null references stores (id),
    incident_date date        not null,
    itm8          varchar(13) not null,
    ean           varchar(14),
    name          varchar(255),
    brand         varchar(255),
    reason        text,
    comment       text,
    incident_code varchar(20),
    ordered_quantity   numeric(14, 3),
    delivered_quantity numeric(14, 3),
    import_id     uuid        not null references incident_imports (id),
    constraint order_incidents_uq unique (store_id, incident_date, itm8)
);

create index order_incidents_store_date_idx on order_incidents (store_id, incident_date);
