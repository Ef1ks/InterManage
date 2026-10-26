-- Dokumenty magazynowe z raportu "Statystyka: Wejścia/Wyjścia" systemu sklepu (kolumny Code nomenclature ... Marge en %).
-- Każdy typ dokumentu (PZ, PL, MM, PPP...) ma własne tabele; na razie zapisujemy tylko PZ, pozostałe linie są liczone
-- jako pominięte. Ponowne wgranie tego samego dokumentu uzupełnia go i aktualizuje - nic nie jest usuwane.

-- Każde udane wgranie raportu (nieudane są wycofywane w całości).
create table document_imports (
    id                  uuid primary key default gen_random_uuid(),
    store_id            uuid         not null references stores (id),
    file_name           varchar(255) not null,
    -- "sam. 24/10/2026 - 16:10:50" z pierwszego wiersza raportu.
    report_generated_at timestamp,
    -- "Wybór danych : OD 20/10/2026 do 24/10/2026".
    period_from         date,
    period_to           date,
    imported_by         uuid         references users (id) on delete set null,
    imported_at         timestamptz  not null default now(),
    total_lines         integer      not null,
    pz_lines            integer      not null,
    pz_created_count    integer      not null, -- dokumenty PZ, których wcześniej nie było
    pz_updated_count    integer      not null, -- dokumenty PZ już znane, uzupełnione o zmiany z tego pliku
    skipped_lines       integer      not null, -- linie typów, których jeszcze nie zapisujemy
    skipped_types       varchar(200)           -- te typy, np. "MM, PL, PPP"
);

create index document_imports_store_idx on document_imports (store_id, imported_at desc);

-- Przyjęcie towaru (PZ): nagłówek wspólny dla wszystkich linii o tym samym "Numéro document interne".
create table pz_documents (
    id                       uuid primary key default gen_random_uuid(),
    store_id                 uuid           not null references stores (id),
    number                   varchar(30)    not null, -- Numéro document interne
    document_date            timestamp      not null, -- Date Document
    supplier_code            varchar(10),             -- Code fournisseur
    supplier_name            varchar(100),            -- Libellé fournisseur
    supplier_document_number varchar(60),             -- Numéro document fournisseur (faktura / WZ dostawcy)
    -- Podsumowanie wszystkich zapisanych linii, przeliczane po każdym imporcie.
    line_count               integer        not null,
    purchase_value           numeric(16, 4) not null, -- suma Valeur FIFO
    sale_value_gross         numeric(16, 4) not null, -- suma Valeur Vente TTC
    first_import_id          uuid           not null references document_imports (id),
    last_import_id           uuid           not null references document_imports (id),
    created_at               timestamptz    not null default now(),
    updated_at               timestamptz    not null default now()
);

create unique index pz_documents_store_number_uq on pz_documents (store_id, number);
create index pz_documents_store_date_idx on pz_documents (store_id, document_date desc);

-- Linie PZ, kluczem jest numer linii w dokumencie (N° ligne).
create table pz_document_lines (
    id                  uuid primary key default gen_random_uuid(),
    document_id         uuid           not null references pz_documents (id),
    line_number         integer        not null, -- N° ligne
    nomenclature_code   varchar(30),             -- Code nomenclature
    nomenclature_name   varchar(60),             -- Libellé nomenclature
    itm8                varchar(13)    not null, -- ITM8
    ean                 varchar(14),             -- EAN13
    name                varchar(100),            -- Libellé article
    brand               varchar(50),             -- Marque
    unit                varchar(20),             -- Unité Mesure (kg, Sztuka, L...)
    quantity            numeric(14, 3) not null, -- Quantité
    purchase_price      numeric(14, 4),          -- Prix achat FIFO
    sale_price_current  numeric(14, 4),          -- Prix Vente actuel
    sale_price_document numeric(14, 4),          -- Prix vente document
    purchase_value      numeric(16, 4),          -- Valeur FIFO
    sale_value_gross    numeric(16, 4),          -- Valeur Vente TTC
    sale_value_net      numeric(16, 4),          -- Valeur Vente HT
    vat_rate            smallint,                -- TVA, stawka w procentach (5, 8, 23...)
    vat_value           numeric(16, 4),          -- Valeur TVA
    margin_value        numeric(16, 4),          -- Marge en valeur
    margin_percent      numeric(12, 2)           -- Marge en %
);

create unique index pz_document_lines_document_line_uq on pz_document_lines (document_id, line_number);
create index pz_document_lines_itm8_idx on pz_document_lines (itm8);

