-- Przypięcia pozycji faktury do artykułów z kartoteki we wprowadzaniu danych. Pozycja bez EAN-u (np. warzywa:
-- w kolumnie EAN jest nazwa z faktury "CUKINIA KR. POCH. PL" albo indeks dostawcy) albo z EAN-em spoza kartoteki
-- zostaje raz przypięta do artykułu; od tej pory ta sama pozycja od tego samego dostawcy (NIP) wczytuje się od razu
-- jako ten artykuł. invoice_code to kod z kolumny EAN po normalizacji (wielkie litery bez polskich znaków, pojedyncze
-- spacje). Odpięcie i zastąpienie innym artykułem to znacznik deleted_at/by; aktywne przypięcie jest jedno na kod.
create table data_entry_product_links (
    id           uuid primary key default gen_random_uuid(),
    store_id     uuid        not null references stores (id),
    seller_nip   varchar(20) not null,
    invoice_code text        not null,
    product_id   uuid        not null references products (id),
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz not null default now(),
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz
);

create unique index data_entry_product_links_active_uq
    on data_entry_product_links (store_id, seller_nip, invoice_code) where deleted_at is null;
