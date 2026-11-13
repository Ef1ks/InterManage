-- Refaktury bazy SCA PR: baza fakturuje towar innych dostawców (np. Artek) i w XML podaje ich kod u siebie
-- (DodatkowyOpis "DDDKodDostawcy", np. 09295) oraz numer faktury dostawcy ("DDDNrFaDostawcy", np. F61577/26/).
-- Kod zapisujemy tak, jak jest w XML; odczytuje go KsefCorrectedInvoiceService.readXml, a tu uzupełniamy faktury,
-- których XML jest już w bazie.
alter table ksef_invoices
    add column base_supplier_code    varchar(20),
    add column base_supplier_invoice varchar(100);

update ksef_invoices i
set base_supplier_code    = left(nullif(btrim(substring(convert_from(x.content, 'UTF8')
        from '<Klucz>\s*DDDKodDostawcy\s*</Klucz>\s*<Wartosc>([^<]*)</Wartosc>')), ''), 20),
    base_supplier_invoice = left(nullif(btrim(substring(convert_from(x.content, 'UTF8')
        from '<Klucz>\s*DDDNrFaDostawcy\s*</Klucz>\s*<Wartosc>([^<]*)</Wartosc>')), ''), 100)
from ksef_invoice_xml x
where x.invoice_id = i.id
  and position('DDDKodDostawcy' in convert_from(x.content, 'UTF8')) > 0;

create index ksef_invoices_base_supplier_code_idx on ksef_invoices (nip, ltrim(base_supplier_code, '0'))
    where base_supplier_code is not null;

-- Kod dostawcy w bazie przypisany do dostawcy (z listy dostawców sklepu albo samą nazwą), wspólnie dla firmy
-- (nip = NIP firmy, jak ksef_invoices.nip). Kod bez zer wiodących. Zmiana i usunięcie to znacznik.
create table ksef_base_supplier_codes
(
    id           uuid primary key,
    nip          varchar(10)  not null,
    code         varchar(20)  not null,
    name         varchar(500) not null,
    supplier_nip varchar(20),
    supplier_id  uuid references suppliers (id) on delete set null,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz  not null,
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz
);

create unique index ksef_base_supplier_codes_active_idx on ksef_base_supplier_codes (nip, code)
    where deleted_at is null;
