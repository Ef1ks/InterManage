-- Plik faktury spoza KSeF (PDF albo zdjęcie/skan JPG, PNG) dołączony do faktury wpisanej ręcznie. Treść w bazie, bo
-- plików jest mało (setki kB na fakturę) i idą do kopii zapasowej razem z resztą. Faktura ma najwyżej jeden
-- aktywny plik; wymiana i usunięcie to znacznik na poprzednim, który zostaje jako historia.
create table document_manual_invoice_files (
    id                uuid primary key,
    manual_invoice_id uuid         not null references document_manual_invoices (id),
    file_name         varchar(255) not null,
    content_type      varchar(100) not null,
    size_bytes        integer      not null,
    content           bytea        not null,
    created_by        uuid references users (id),
    created_at        timestamptz  not null,
    removed_by        uuid references users (id),
    removed_at        timestamptz
);

create index document_manual_invoice_files_invoice_idx on document_manual_invoice_files (manual_invoice_id);
create unique index document_manual_invoice_files_active_uq on document_manual_invoice_files (manual_invoice_id)
    where removed_at is null;
