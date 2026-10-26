-- Faktury zakupowe z KSeF (Krajowy System e-Faktur). Każdy sklep łączy się sam: certyfikat KSeF z kluczem i hasłem,
-- NIP firmy jako kontekst. Faktury idą na NIP firmy, a sklep rozpoznaje swoje po identyfikatorach wewnętrznych
-- (IDWew, Podmiot3 na fakturze): widzi faktury ze swoimi IDWew i wszystkie bez IDWew.

create table ksef_connections (
    store_id             uuid primary key references stores (id),
    nip                  varchar(10)  not null,
    certificate_pem      text         not null,
    -- Klucz prywatny tak, jak przyszedł z KSeF (zaszyfrowany hasłem), hasło zaszyfrowane kluczem aplikacji.
    private_key_pem      text         not null,
    password_encrypted   text         not null,
    certificate_subject  varchar(300) not null,
    certificate_valid_to timestamptz  not null,
    updated_by           uuid references users (id) on delete set null,
    updated_at           timestamptz  not null,
    tested_at            timestamptz,
    test_ok              boolean,
    test_message         varchar(1000),
    -- Pobieranie przyrostowe po dacie trwałego zapisu w KSeF: faktury do tej chwili są już w bazie.
    sync_cursor          timestamptz,
    synced_at            timestamptz,
    sync_ok              boolean,
    sync_message         varchar(1000)
);

-- IDWew, które sklep uznaje za swoje. Usunięcie to znacznik.
create table ksef_internal_ids (
    id          uuid primary key,
    store_id    uuid        not null references stores (id),
    internal_id varchar(16) not null,
    name        varchar(150),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    removed_by  uuid references users (id) on delete set null,
    removed_at  timestamptz
);
create unique index ksef_internal_ids_active on ksef_internal_ids (store_id, internal_id) where removed_at is null;

-- Dostawcy, których faktur sklep nie chce widzieć na liście (np. media, leasing). Usunięcie to znacznik.
create table ksef_skipped_suppliers (
    id          uuid primary key,
    store_id    uuid         not null references stores (id),
    nip         varchar(10)  not null,
    name        varchar(512),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz  not null,
    removed_by  uuid references users (id) on delete set null,
    removed_at  timestamptz
);
create unique index ksef_skipped_suppliers_active on ksef_skipped_suppliers (store_id, nip) where removed_at is null;

create table ksef_invoices (
    id                     uuid primary key,
    store_id               uuid           not null references stores (id),
    ksef_number            varchar(36)    not null,
    invoice_number         varchar(256)   not null,
    issue_date             date           not null,
    invoicing_date         timestamptz    not null,
    permanent_storage_date timestamptz    not null,
    seller_nip             varchar(10)    not null,
    seller_name            varchar(512),
    buyer_identifier       varchar(50),
    buyer_name             varchar(512),
    net_amount             numeric(16, 2) not null,
    gross_amount           numeric(16, 2) not null,
    vat_amount             numeric(16, 2) not null,
    currency               varchar(3)     not null,
    invoice_type           varchar(12)    not null,
    has_attachment         boolean        not null,
    -- Ręczne oznaczenie "wprowadzona" (gdy numeru nie ma w PZ albo różni się zapisem).
    entered_by             uuid references users (id) on delete set null,
    entered_at             timestamptz,
    created_at             timestamptz    not null,
    updated_at             timestamptz    not null,
    unique (store_id, ksef_number)
);
create index ksef_invoices_store_issue on ksef_invoices (store_id, issue_date);

-- Podmioty trzecie z identyfikatorem wewnętrznym (IDWew) - po nich sklep rozpoznaje swoje faktury.
create table ksef_invoice_internal_ids (
    invoice_id  uuid        not null references ksef_invoices (id) on delete cascade,
    internal_id varchar(16) not null,
    name        varchar(512),
    primary key (invoice_id, internal_id)
);
create index ksef_invoice_internal_ids_id on ksef_invoice_internal_ids (internal_id);

-- Faktura jest wprowadzona, gdy jej numer jest numerem dokumentu dostawcy w przyjęciu PZ sklepu.
create index documents_supplier_document_number
    on documents (store_id, upper(supplier_document_number)) where type = 'PZ';
