-- Zakup mięsa z dokumentów WZ dostawców (moduł meat). Faktura od SCA PR przychodzi po kilku dniach, a cenę zakupu
-- trzeba znać od razu, więc pracownik wgrywa skan albo zdjęcie WZ, Gemini je odczytuje, a pracownik zatwierdza.
-- Liczy się tylko cena zakupu netto za kg: ilości i sum nie zapisujemy.

-- Skan albo zdjęcie dokumentu: zapisywane przy odczycie, przypinane do dokumentu przy zapisie. Plików jest mało,
-- więc treść leży w bazie i idzie do kopii razem z nią.
create table meat_delivery_scans (
    id           uuid primary key default gen_random_uuid(),
    store_id     uuid         not null references stores (id),
    file_name    varchar(255) not null,
    content_type varchar(50)  not null,
    size         bigint       not null,
    content      bytea        not null,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz  not null default now()
);

-- Dokument dostawy: dostawca mięsa (firma z dokumentu, nie SCA PR, które fakturuje), numer i data wysyłki.
-- Usunięcie to znacznik deleted_at/by. Ten sam dokument dostawcy może być w sklepie aktywny tylko raz.
create table meat_deliveries (
    id              uuid primary key default gen_random_uuid(),
    store_id        uuid         not null references stores (id),
    supplier_id     uuid         not null references suppliers (id),
    document_number varchar(100) not null,
    delivery_date   date         not null,
    scan_id         uuid references meat_delivery_scans (id),
    created_by      uuid references users (id) on delete set null,
    created_at      timestamptz  not null default now(),
    updated_by      uuid references users (id) on delete set null,
    updated_at      timestamptz  not null default now(),
    deleted_by      uuid references users (id) on delete set null,
    deleted_at      timestamptz
);

create unique index meat_deliveries_document_uq
    on meat_deliveries (store_id, supplier_id, upper(document_number)) where deleted_at is null;
create index meat_deliveries_store_date_idx on meat_deliveries (store_id, delivery_date);

-- Pozycja dokumentu: nazwa i kod tak, jak są na WZ, cena netto za kg i artykuł z kartoteki. Bez artykułu
-- (product_id pusty) pozycja to sam zakup: raport pokazuje cenę zakupu bez ceny sprzedaży.
create table meat_delivery_lines (
    id                     uuid primary key default gen_random_uuid(),
    delivery_id            uuid           not null references meat_deliveries (id),
    position               int            not null,
    document_name          text           not null,
    document_code          varchar(50),
    unit_price             numeric(12, 4) not null,
    product_id             uuid references products (id),
    handwritten_correction boolean        not null default false,
    note                   text
);

create index meat_delivery_lines_delivery_idx on meat_delivery_lines (delivery_id, position);

-- Zapamiętane znaki dostawcy z dokumentów (numer weterynaryjny, BDO, NIP): nagłówek WZ bywa ucięty albo zasłonięty
-- pieczątką, więc po pierwszym wyborze dostawcy kolejny dokument z tym znakiem rozpoznaje się sam. Aktywny jeden na
-- znak; zmiana dostawcy to znacznik na poprzednim wpisie.
create table meat_supplier_marks (
    id          uuid primary key default gen_random_uuid(),
    store_id    uuid        not null references stores (id),
    kind        varchar(20) not null,
    value       varchar(50) not null,
    supplier_id uuid        not null references suppliers (id),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null default now(),
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz
);

create unique index meat_supplier_marks_active_uq
    on meat_supplier_marks (store_id, kind, value) where deleted_at is null;

-- Zapamiętane przypisania pozycji z WZ do artykułu z kartoteki, per dostawca ("LIGAWA" od Beef&Pork to wołowina,
-- "LIGAWA WP" od Waldi wieprzowina). document_key: kod z dokumentu albo nazwa (wielkie litery bez polskich znaków).
-- product_id pusty = pozycja bez artykułu (sam zakup). Zmiana to znacznik na poprzednim wpisie.
create table meat_product_links (
    id           uuid primary key default gen_random_uuid(),
    store_id     uuid        not null references stores (id),
    supplier_id  uuid        not null references suppliers (id),
    document_key text        not null,
    product_id   uuid references products (id),
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz not null default now(),
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz
);

create unique index meat_product_links_active_uq
    on meat_product_links (store_id, supplier_id, document_key) where deleted_at is null;
