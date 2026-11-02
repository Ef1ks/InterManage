-- Pliki CTRLPRIX z wprowadzania danych: przy zapisie pliku XML faktury artykuły z kartoteki, którym w tabeli zmieniono
-- cenę sprzedaży, idą do pliku zmiany cen dla programu sklepu (folder plików PROTO). Treść zostaje w bazie, żeby plik
-- dało się zapisać ponownie; okno pokazuje 10 ostatnich plików sklepu, starsze zostają jako historia.
-- supplier_code / supplier_keyword / supplier_name to kopia dostawcy z chwili zapisu (hasło dostawcy jest w nazwie).
create table data_entry_price_control_files (
    id               uuid primary key default gen_random_uuid(),
    store_id         uuid        not null references stores (id),
    supplier_id      uuid references suppliers (id),
    supplier_code    varchar(10),
    supplier_keyword varchar(30) not null,
    supplier_name    text,
    invoice_number   text,
    file_name        text        not null,
    line_count       integer     not null,
    content          bytea       not null,
    created_by       uuid references users (id) on delete set null,
    created_at       timestamptz not null default now()
);

create index data_entry_price_control_files_store_idx on data_entry_price_control_files (store_id, created_at desc);
