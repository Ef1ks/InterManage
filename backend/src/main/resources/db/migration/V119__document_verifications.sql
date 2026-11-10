-- Weryfikacja przyjęcia PZ: pracownik oznacza je „w trakcie weryfikacji” (czeka na kierownika) albo „zweryfikowane”.
-- Obowiązujący status jest przy dokumencie (lista i filtr), a każda zmiana, także zdjęcie statusu, trafia do historii.
alter table documents
    add column verification_status varchar(20),
    add column verification_at     timestamptz,
    add column verification_by     uuid references users (id) on delete set null,
    add constraint documents_verification_status check (verification_status in ('IN_PROGRESS', 'VERIFIED'));

create index documents_verification_idx on documents (store_id, type, verification_status)
    where verification_status is not null;

create table document_verification_changes (
    id          uuid primary key default gen_random_uuid(),
    document_id uuid        not null references documents (id),
    -- pusty = status zdjęty
    status      varchar(20),
    changed_at  timestamptz not null default now(),
    changed_by  uuid references users (id) on delete set null,
    constraint document_verification_changes_status check (status in ('IN_PROGRESS', 'VERIFIED'))
);

create index document_verification_changes_document_idx on document_verification_changes (document_id, changed_at);
