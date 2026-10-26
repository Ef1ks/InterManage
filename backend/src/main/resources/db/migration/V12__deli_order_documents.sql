-- Zamówienie garmażu jedzie do sklepu na dokumencie ruchu międzymagazynowego (MM) wystawionym przez dostawcę.
-- Dostawca przypina ten dokument (jeden albo kilka) do zamówienia. Odpięcie niczego nie kasuje: wpis dostaje
-- unlinked_at / unlinked_by i zostaje w historii; ponowne przypięcie to nowy wpis.
create table deli_order_documents (
    id          uuid primary key default gen_random_uuid(),
    order_id    uuid        not null references deli_orders (id),
    document_id uuid        not null references documents (id),
    linked_by   uuid        references users (id) on delete set null,
    linked_at   timestamptz not null default now(),
    unlinked_by uuid        references users (id) on delete set null,
    unlinked_at timestamptz
);

-- Ten sam dokument przypięty do tego samego zamówienia najwyżej raz naraz.
create unique index deli_order_documents_active_uq on deli_order_documents (order_id, document_id) where unlinked_at is null;
create index deli_order_documents_document_idx on deli_order_documents (document_id) where unlinked_at is null;
