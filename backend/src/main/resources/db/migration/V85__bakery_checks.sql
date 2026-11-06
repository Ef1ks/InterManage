-- Weryfikacja piekarni (moduł bakery): ilości z papierowych WZ dostawcy kontra faktura z korektami i przyjęcie PZ po
-- korektach. Faktura zbiorcza obejmuje kilka WZ, a pozycje podaje już zsumowane, więc błąd WZ widać dopiero po wpisaniu
-- ilości z każdego dokumentu. Usunięcie weryfikacji, WZ i odpięcie bonu albo faktury to znacznik.
create table bakery_checks (
    id          uuid primary key,
    store_id    uuid        not null references stores (id),
    supplier_id uuid        not null references suppliers (id),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz
);

create index bakery_checks_store_supplier_idx on bakery_checks (store_id, supplier_id, created_at desc);

-- Przyjęcia PZ weryfikacji: z nich idą faktury, a z faktur kafelki WZ.
create table bakery_check_documents (
    id          uuid primary key,
    check_id    uuid        not null references bakery_checks (id),
    document_id uuid        not null references documents (id),
    added_by    uuid references users (id) on delete set null,
    added_at    timestamptz not null,
    removed_by  uuid references users (id) on delete set null,
    removed_at  timestamptz
);

create unique index bakery_check_documents_uq on bakery_check_documents (check_id, document_id)
    where removed_at is null;
create index bakery_check_documents_document_idx on bakery_check_documents (document_id) where removed_at is null;

-- Faktury przypięte do weryfikacji. Ich korekty liczą się w locie, po fakturze korygowanej z XML korekty, więc korekta
-- wystawiona później sama trafia do porównania; korektę przypina się ręcznie tylko, gdy jej XML nie jest jeszcze odczytany.
create table bakery_check_invoices (
    id         uuid primary key,
    check_id   uuid        not null references bakery_checks (id),
    invoice_id uuid        not null references ksef_invoices (id),
    added_by   uuid references users (id) on delete set null,
    added_at   timestamptz not null,
    removed_by uuid references users (id) on delete set null,
    removed_at timestamptz
);

create unique index bakery_check_invoices_uq on bakery_check_invoices (check_id, invoice_id) where removed_at is null;

-- Skan albo zdjęcie WZ do odczytu przez AI. Treść w bazie, jak skany mięsa: plików jest mało i idą do kopii razem z nią.
create table bakery_wz_scans (
    id           uuid primary key,
    store_id     uuid         not null references stores (id),
    file_name    varchar(255) not null,
    content_type varchar(50)  not null,
    size         bigint       not null,
    content      bytea        not null,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz  not null
);

-- Dokument WZ: kafelek w weryfikacji. Numer i data z pola WZ faktury (source_invoice_id) albo wpisane ręcznie;
-- date_estimated = data wyliczona z okresu faktury, bo dostawca podaje same numery (Sofian).
create table bakery_wz (
    id                uuid primary key,
    check_id          uuid        not null references bakery_checks (id),
    position          int         not null,
    number            varchar(100),
    delivery_date     date,
    date_estimated    boolean     not null default false,
    source_invoice_id uuid references ksef_invoices (id),
    scan_id           uuid references bakery_wz_scans (id),
    created_by        uuid references users (id) on delete set null,
    created_at        timestamptz not null,
    deleted_by        uuid references users (id) on delete set null,
    deleted_at        timestamptz
);

create index bakery_wz_check_idx on bakery_wz (check_id);

-- Ilość artykułu na WZ; artykuły bez ilości nie mają wiersza. manual = dopisany spoza listy dostawcy (zostaje też bez
-- ilości), scanned_quantity = ilość odczytana ze skanu, żeby widzieć i mierzyć, co poprawił pracownik.
create table bakery_wz_lines (
    id               uuid primary key,
    wz_id            uuid    not null references bakery_wz (id),
    product_id       uuid    not null references products (id),
    quantity         numeric(12, 3),
    scanned_quantity numeric(12, 3),
    manual           boolean not null default false
);

create unique index bakery_wz_lines_uq on bakery_wz_lines (wz_id, product_id);
