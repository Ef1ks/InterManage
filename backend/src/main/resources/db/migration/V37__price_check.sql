-- Weryfikacja cen wyższych niż baza. CENA_CAD z raportu kartoteki: cena sprzedaży sztuki narzucona przez centralę
-- (cadencier). Artykuł z tą ceną (albo dostarczany przez bazę, dostawca 538) kupiony u innego dostawcy tak drogo,
-- że marża na bonie spada poniżej minimum sklepu, trafia do raportu.
alter table products add column cadencier_price numeric(12, 2);

-- Minimalna akceptowana marża (% od sprzedaży netto) przy towarze z bazy kupionym u innego dostawcy.
create table price_check_settings (
    id                 uuid primary key default gen_random_uuid(),
    store_id           uuid        not null references stores (id),
    min_margin_percent numeric(5, 2),
    updated_by         uuid references users (id) on delete set null,
    updated_at         timestamptz not null default now(),
    constraint price_check_settings_store_uq unique (store_id),
    constraint price_check_settings_margin check (min_margin_percent between -100 and 100)
);

-- Wyjaśnianie przyjęcia z dostawcą: każda zmiana statusu to nowy wpis, obowiązuje najnowszy nieusunięty.
-- Brak wpisu = do wyjaśnienia. Cofnięcie zmiany to znacznik (deleted_at), wtedy wraca poprzedni status.
create table price_check_reviews (
    id          uuid primary key default gen_random_uuid(),
    store_id    uuid         not null references stores (id),
    document_id uuid         not null references documents (id),
    status      varchar(20)  not null,
    note        varchar(1000),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz  not null default now(),
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz
);

create index price_check_reviews_document_idx on price_check_reviews (document_id, created_at);
create index price_check_reviews_store_idx on price_check_reviews (store_id);
