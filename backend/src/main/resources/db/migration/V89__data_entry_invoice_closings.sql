-- Kolejka faktur do zamknięcia w programie sklepu: każda faktura z pliku XML zapisanego na dysku trafia tu raz (kolejny
-- zapis tej samej faktury nadpisuje plik, więc nowego wpisu nie ma, także po zamknięciu i usunięciu). Historia plików ma
-- tylko 100 ostatnich, a kolejka nie może niczego zgubić. generated_at: faktura poszła już do skryptu zamknięcia,
-- deleted_at: usunięta z listy wygenerowanych, do przywrócenia.
create table data_entry_invoice_closings (
    id              uuid primary key default gen_random_uuid(),
    store_id        uuid          not null references stores (id),
    export_id       uuid          not null references data_entry_xml_exports (id),
    supplier_code   text          not null default '',
    document_number text          not null,
    supplier_name   text,
    document_date   date          not null,
    net_value       numeric(14, 2) not null,
    created_by      uuid references users (id) on delete set null,
    created_at      timestamptz   not null default now(),
    generated_by    uuid references users (id) on delete set null,
    generated_at    timestamptz,
    deleted_by      uuid references users (id) on delete set null,
    deleted_at      timestamptz,
    constraint data_entry_invoice_closings_key unique (store_id, supplier_code, document_number)
);
