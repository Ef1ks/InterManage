-- Dokument sklepu (przyjęcie PZ, korekta od dostawcy WZ) przypięty ręcznie do faktury z KSeF. Przyjęcie z numerem
-- faktury łączy się samo (po numerze dokumentu dostawcy); przypięcie jest dla dokumentów bez numeru albo z innym.
-- Odpięcie to znacznik (unlinked_at/by), wpis zostaje.
create table document_ksef_invoices (
    id          uuid primary key,
    document_id uuid        not null references documents (id),
    invoice_id  uuid        not null references ksef_invoices (id),
    linked_by   uuid references users (id),
    linked_at   timestamptz not null,
    unlinked_by uuid references users (id),
    unlinked_at timestamptz
);

create unique index document_ksef_invoices_active_uq
    on document_ksef_invoices (document_id, invoice_id) where unlinked_at is null;
create index document_ksef_invoices_invoice_idx on document_ksef_invoices (invoice_id) where unlinked_at is null;
