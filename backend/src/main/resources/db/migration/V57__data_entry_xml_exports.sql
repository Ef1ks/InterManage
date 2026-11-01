-- Pliki XML faktur wygenerowane we wprowadzaniu danych ("Zapisz XML"): treść pliku i opis z nagłówka. Okno
-- wczytywania pokazuje 100 ostatnich plików sklepu; każdy da się wczytać z powrotem do tabeli albo pobrać ponownie.
-- invoice_id to faktura z KSeF wczytana do tabeli w chwili zapisu (brak = plik bez faktury z KSeF).
create table data_entry_xml_exports (
    id              uuid primary key default gen_random_uuid(),
    store_id        uuid          not null references stores (id),
    invoice_id      uuid references ksef_invoices (id),
    file_name       text          not null,
    document_number text,
    document_date   date          not null,
    supplier_name   text,
    supplier_nip    varchar(20),
    line_count      integer       not null,
    net_value       numeric(14, 2) not null,
    content         bytea         not null,
    created_by      uuid references users (id) on delete set null,
    created_at      timestamptz   not null default now()
);

create index data_entry_xml_exports_store_idx on data_entry_xml_exports (store_id, created_at desc);
