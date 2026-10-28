-- Sklepy zaprzyjaźnione: sklep zaprasza inny sklep, zaproszony akceptuje. Zaakceptowana para działa w obie strony
-- (każdy z nich może wskazać drugiego w ruchu międzymagazynowym). Zakończenie, odrzucenie i cofnięcie zaproszenia
-- to znacznik; para sklepów ma najwyżej jeden wpis bez znacznika.
create table store_partners (
    id               uuid primary key default gen_random_uuid(),
    store_id         uuid        not null references stores (id),
    partner_store_id uuid        not null references stores (id),
    invited_by       uuid references users (id) on delete set null,
    invited_at       timestamptz not null default now(),
    accepted_by      uuid references users (id) on delete set null,
    accepted_at      timestamptz,
    deleted_by       uuid references users (id) on delete set null,
    deleted_at       timestamptz,
    constraint store_partners_not_self check (store_id <> partner_store_id)
);

create unique index store_partners_active_pair_uq
    on store_partners (least(store_id, partner_store_id), greatest(store_id, partner_store_id))
    where deleted_at is null;
create index store_partners_partner_idx on store_partners (partner_store_id);

-- Sklep, dla którego wystawiono ruch międzymagazynowy (MM). Sklep docelowy widzi dokument do wprowadzenia u siebie
-- i oznacza go jako wprowadzony. Zmiana sklepu cofa poprzedni wpis (znacznik) i dodaje nowy.
create table document_transfer_targets (
    id              uuid primary key default gen_random_uuid(),
    document_id     uuid        not null references documents (id),
    target_store_id uuid        not null references stores (id),
    created_by      uuid references users (id) on delete set null,
    created_at      timestamptz not null default now(),
    entered_by      uuid references users (id) on delete set null,
    entered_at      timestamptz,
    deleted_by      uuid references users (id) on delete set null,
    deleted_at      timestamptz
);

create unique index document_transfer_targets_active_uq on document_transfer_targets (document_id)
    where deleted_at is null;
create index document_transfer_targets_target_idx on document_transfer_targets (target_store_id)
    where deleted_at is null;
