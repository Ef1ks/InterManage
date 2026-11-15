-- Numery WZ z faktury (elementy <WZ> w XML), tak jak je wystawił dostawca (np. 09516050250 w fakturze zbiorczej bazy,
-- "WZ-318262/26/01SP"). Po nich wyszukiwarka faktur znajduje fakturę z numerem WZ z karty reklamacyjnej, a razem z nią
-- korekty tej faktury. Odczytuje je KsefCorrectedInvoiceService.readXml; tu uzupełniamy faktury z XML w bazie.
create table ksef_invoice_delivery_notes (
    invoice_id uuid         not null references ksef_invoices (id) on delete cascade,
    number     varchar(100) not null,
    primary key (invoice_id, number)
);

insert into ksef_invoice_delivery_notes (invoice_id, number)
select distinct x.invoice_id, left(btrim(replace(m[1], '&amp;', '&')), 100)
from ksef_invoice_xml x,
     regexp_matches(convert_from(x.content, 'UTF8'), '<WZ>([^<]*)</WZ>', 'g') m
where btrim(m[1]) <> '';
