-- Weryfikacja inwentury (moduł stocktake): raport "Porównanie stanów rzeczywistych i teoretycznych" z systemu sklepu,
-- wgrywany w całości albo po dziale, zanim inwentura zostanie zamknięta. Usunięcie inwentury to znacznik.
create table stocktakes (
    id             uuid primary key,
    store_id       uuid         not null references stores (id),
    number         varchar(20)  not null, -- "Numer : 100" z nagłówka raportu
    name           varchar(200) not null, -- "INWENT POLROCZ 2026"
    stocktake_date date         not null,
    created_at     timestamptz  not null,
    deleted_by     uuid references users (id) on delete set null,
    deleted_at     timestamptz
);

create unique index stocktakes_uq on stocktakes (store_id, number, stocktake_date);

create table stocktake_imports (
    id                  uuid primary key,
    stocktake_id        uuid           not null references stocktakes (id),
    file_name           varchar(255)   not null,
    selection           varchar(200),            -- "Ramka wyboru" z pierwszej strony
    whole_store         boolean        not null, -- raport całego sklepu: artykuł spoza niego nie ma już różnicy
    report_generated_at timestamp      not null,
    page_count          integer        not null,
    line_count          integer        not null,
    difference_value    numeric(14, 2) not null,
    created_count       integer        not null default 0,
    updated_count       integer        not null default 0,
    resolved_count      integer        not null default 0,
    imported_by         uuid references users (id) on delete set null,
    imported_at         timestamptz    not null
);

create index stocktake_imports_stocktake_idx on stocktake_imports (stocktake_id, imported_at desc);

-- Artykuł raz w inwenturze. Raporty dzielone na działy scalają się po ITM8, a starszy wydruk nie nadpisuje nowszego
-- (report_generated_at). Pola first_* to różnica z pierwszego wydruku, żeby było widać, co dały przeliczenia.
-- Raport pokazuje "jedynie rozbieżności", więc poprawiony artykuł znika z pełnego wydruku: dostaje wtedy
-- resolved_import_id i zostaje jako historia.
create table stocktake_lines (
    stocktake_id           uuid           not null references stocktakes (id),
    itm8                   varchar(13)    not null,
    ean                    varchar(14),
    name                   text           not null,
    brand                  text,
    block_type             text,
    real_quantity          numeric(14, 3) not null,
    theoretical_quantity   numeric(14, 3) not null,
    difference_quantity    numeric(14, 3) not null,
    difference_value       numeric(14, 3) not null,
    article_kind           text,
    first_difference_value numeric(14, 3) not null,
    report_generated_at    timestamp      not null,
    first_import_id        uuid           not null references stocktake_imports (id),
    last_import_id         uuid           not null references stocktake_imports (id),
    resolved_import_id     uuid references stocktake_imports (id),
    primary key (stocktake_id, itm8)
);
