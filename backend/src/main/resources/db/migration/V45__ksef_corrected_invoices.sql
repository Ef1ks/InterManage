-- Faktury korygowane przez korektę z KSeF (DaneFaKorygowanej z XML korekty): po nich korekta trafia do faktury,
-- a przez fakturę do przyjęcia PZ. Metadane z KSeF tego nie podają, więc XML korekt pobiera się w tle.
create table ksef_invoice_corrected (
    invoice_id     uuid         not null references ksef_invoices (id) on delete cascade,
    invoice_number varchar(256),
    ksef_number    varchar(36),
    issue_date     date
);

create index ksef_invoice_corrected_invoice on ksef_invoice_corrected (invoice_id);
create index ksef_invoice_corrected_ksef_number on ksef_invoice_corrected (ksef_number);
create index ksef_invoice_corrected_number on ksef_invoice_corrected (upper(invoice_number));

-- Kiedy odczytano faktury korygowane z XML korekty (null = jeszcze nie) i błąd, gdy XML nie dał się pobrać
-- albo odczytać (wtedy korekta nie jest pobierana ponownie).
alter table ksef_invoices
    add column corrected_read_at timestamptz,
    add column corrected_error   varchar(500);

create index ksef_invoices_unread_corrections on ksef_invoices (nip, issue_date desc)
    where corrected_read_at is null and invoice_type like 'Kor%';
