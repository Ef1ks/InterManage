-- XML faktury z KSeF zapisany przy pierwszym pobraniu (PDF, XML). Faktura w KSeF się nie zmienia, a pobieranie
-- pojedynczych faktur ma w KSeF niski limit (64 na godzinę na NIP), więc kolejne pobrania idą z bazy.
create table ksef_invoice_xml (
    invoice_id    uuid primary key references ksef_invoices (id) on delete cascade,
    content       bytea       not null,
    downloaded_at timestamptz not null
);
