-- Szkic artykułu: nowy artykuł z pliku CADBEZP, który pracownik potwierdził jako poprawnie wczytany przez program
-- sklepu. Dane są takie, jak poszły w pliku (ITM8 nadaje dopiero system sklepu). Pierwszy raport kartoteki
-- wygenerowany po potwierdzeniu rozstrzyga szkic: artykuł z tym EAN-em w raporcie = matched_* (reszta danych jest
-- już w products), brak = removed_reason MISSING (program sklepu artykułu nie założył). Nic nie znika: anulowanie
-- (CANCELLED) i zastąpienie nowszym szkicem tego samego EAN-u (REPLACED) to też znaczniki.
create table product_drafts (
    id                 uuid primary key default gen_random_uuid(),
    store_id           uuid           not null references stores (id),
    ean                varchar(14)    not null, -- EAN z pliku CADBEZP bez zer wiodących
    name               varchar(500),
    brand              varchar(100),
    unit               varchar(4),               -- rodzaj sprzedaży: kg, L, szt.
    net_content        numeric(12, 3),
    purchase_price     numeric(12, 4),           -- cena zakupu netto sztuki
    vat_percent        numeric(5, 2),
    sale_price         numeric(12, 2),           -- cena sprzedaży brutto
    subfamily_code     varchar(6),
    cn                 varchar(20),
    supplier_id        uuid references suppliers (id),
    supplier_name      varchar(300),
    invoice_number     varchar(100),
    file_name          varchar(255)   not null,  -- plik CADBEZP
    replaces_id        uuid references product_drafts (id),
    created_by         uuid references users (id) on delete set null,
    created_at         timestamptz    not null default now(),
    matched_product_id uuid references products (id),
    matched_import_id  uuid references product_imports (id),
    matched_at         timestamptz,
    removed_reason     varchar(20),              -- MISSING, CANCELLED, REPLACED
    removed_import_id  uuid references product_imports (id),
    removed_by         uuid references users (id) on delete set null,
    removed_at         timestamptz
);

-- Oczekujący szkic EAN-u jest w sklepie najwyżej jeden.
create unique index product_drafts_pending on product_drafts (store_id, ean)
    where matched_at is null and removed_at is null;

alter table product_imports
    add column drafts_matched_count integer not null default 0,
    add column drafts_removed_count integer not null default 0;
