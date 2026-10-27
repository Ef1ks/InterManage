-- Faktura spoza KSeF (dostawca bez KSeF, faktura papierowa) wpisana ręcznie przy przyjęciu PZ albo korekcie (PK, WZ):
-- dostawca z listy dostawców sklepu i numer faktury. Przyjęcie ma najwyżej jedną fakturę (z KSeF albo tę), pilnuje
-- serwis. Usunięcie to znacznik.
create table document_manual_invoices (
    id             uuid primary key,
    document_id    uuid        not null references documents (id),
    supplier_id    uuid        not null references suppliers (id),
    invoice_number varchar(60) not null,
    issue_date     date,
    created_by     uuid references users (id),
    created_at     timestamptz not null,
    removed_by     uuid references users (id),
    removed_at     timestamptz
);

create index document_manual_invoices_document_idx on document_manual_invoices (document_id);

-- Ręczne połączenie w porównaniu przyjęcia PZ z fakturą: pozycje faktury (klucz "ean:<EAN bez zer wiodących>" albo
-- "name:<nazwa>" dla pozycji bez kodu) i artykuły przyjęcia (ITM8), które są tym samym towarem, np. jedna pozycja
-- przyjęcia rozbita na fakturze na dwie albo zły kod od dostawcy. Rozłączenie to znacznik.
create table invoice_comparison_matches (
    id           uuid primary key,
    document_id  uuid        not null references documents (id),
    invoice_keys text[]      not null,
    itm8s        text[]      not null,
    created_by   uuid references users (id),
    created_at   timestamptz not null,
    removed_by   uuid references users (id),
    removed_at   timestamptz
);

create index invoice_comparison_matches_document_idx on invoice_comparison_matches (document_id);
