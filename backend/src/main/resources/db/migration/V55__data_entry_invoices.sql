-- Faktury w oknie wczytywania wprowadzania danych (osobno od modułu Faktury KSeF).
--
-- data_entry_invoice_marks: co sklep zrobił z fakturą: LOADED wczytana do tabeli, DOWNLOADED pobrana albo wydrukowana,
-- NOT_MINE oznaczona jako nie moja. Faktura z którymkolwiek aktywnym znacznikiem znika z listy "Do wczytania" (zostaje
-- w "Wczytane"); znaczniki innych sklepów firmy (faktury są wspólne dla NIP-u) lista pokazuje jako informację.
-- Powrót faktury na listę to znacznik deleted_at/by na znacznikach sklepu.
create table data_entry_invoice_marks (
    id         uuid primary key default gen_random_uuid(),
    store_id   uuid        not null references stores (id),
    invoice_id uuid        not null references ksef_invoices (id),
    kind       varchar(20) not null,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz not null default now(),
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create unique index data_entry_invoice_marks_active_uq
    on data_entry_invoice_marks (store_id, invoice_id, kind) where deleted_at is null;
create index data_entry_invoice_marks_invoice_idx
    on data_entry_invoice_marks (invoice_id) where deleted_at is null;

-- Dostawcy (NIP sprzedawcy), których faktur sklep nie wprowadza: nie ma ich w oknie wczytywania. Niezależne od
-- pomijanych dostawców w Fakturach KSeF. Usunięcie z listy to znacznik.
create table data_entry_skipped_suppliers (
    id          uuid primary key default gen_random_uuid(),
    store_id    uuid        not null references stores (id),
    seller_nip  varchar(20) not null,
    seller_name text,
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null default now(),
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz
);

create unique index data_entry_skipped_suppliers_active_uq
    on data_entry_skipped_suppliers (store_id, seller_nip) where deleted_at is null;
