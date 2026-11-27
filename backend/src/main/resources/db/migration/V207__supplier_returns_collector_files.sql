-- Zwroty do dostawcy (Zakupy): lista artykułów do oddania dostawcy, z kolektora (protokół „Zwrot do dostawcy”)
-- albo z Protokołów. Prowadzi się ją statusami do rozliczenia, z bonem dostawy, z którą towar przyjechał, bonem korekty
-- (korekta od dostawcy albo błąd sklepu z raportu dokumentów) i korektą z KSeF. Usunięcie to znacznik.
create table supplier_returns (
    id                     uuid primary key,
    store_id               uuid        not null references stores (id),
    supplier_id            uuid references suppliers (id),
    status                 varchar(20) not null check (status in
                               ('NEW', 'REPORTED', 'IN_PROGRESS', 'ACCEPTED', 'CORRECTED', 'REJECTED')),
    source                 varchar(20) not null check (source in ('COLLECTOR', 'PROTOCOLS')),
    note                   text,
    delivery_document_id   uuid references documents (id),
    correction_document_id uuid references documents (id),
    ksef_invoice_id        uuid references ksef_invoices (id),
    created_by             uuid references users (id) on delete set null,
    created_at             timestamptz not null,
    updated_at             timestamptz not null,
    deleted_by             uuid references users (id) on delete set null,
    deleted_at             timestamptz
);

create index supplier_returns_store on supplier_returns (store_id, created_at desc);

create table supplier_return_lines (
    id        uuid primary key,
    return_id uuid           not null references supplier_returns (id),
    position  integer        not null,
    itm8      varchar(13),
    ean       varchar(14),
    name      varchar(200)   not null,
    brand     varchar(100),
    quantity  numeric(14, 3) not null,
    constraint supplier_return_lines_position unique (return_id, position)
);

-- Historia zwrotu, tylko dopisywana: zmiany statusu, podpięcia i odpięcia bonów i korekt (także automatyczne).
create table supplier_return_events (
    id         uuid primary key,
    return_id  uuid         not null references supplier_returns (id),
    kind       varchar(30)  not null,
    detail     varchar(500),
    created_by uuid references users (id) on delete set null,
    created_at timestamptz  not null
);

create index supplier_return_events_return on supplier_return_events (return_id, created_at);

-- Inwentaryzacje i zamówienia z kolektora: plik dla programu sklepu zrobiony od razu (WGRANY) albo przygotowany na
-- później (CZEKA), wtedy plik powstaje z historii. Usunięcie to znacznik.
create table collector_files (
    id         uuid primary key,
    store_id   uuid        not null references stores (id),
    kind       varchar(20) not null check (kind in ('INVENTORY', 'ORDER')),
    status     varchar(20) not null check (status in ('WAITING', 'LOADED')),
    note       varchar(200),
    file_name  varchar(200),
    loaded_by  uuid references users (id) on delete set null,
    loaded_at  timestamptz,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz not null,
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create index collector_files_store on collector_files (store_id, created_at desc);

create table collector_file_lines (
    id       uuid primary key,
    file_id  uuid           not null references collector_files (id),
    position integer        not null,
    itm8     varchar(13),
    ean      varchar(14)    not null,
    name     varchar(200)   not null,
    brand    varchar(100),
    quantity numeric(14, 3) not null,
    constraint collector_file_lines_position unique (file_id, position)
);

-- Z których list kolektora powstał plik albo zwrot.
create table collector_file_sources (
    file_id uuid not null references collector_files (id),
    list_id uuid not null references collector_lists (id),
    primary key (file_id, list_id)
);
