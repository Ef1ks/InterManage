-- Przeceny artykułów z karty artykułu w Magazynie: cena i okres idą makrem do taryf sprzedaży w programie sklepu, a wpis
-- czeka tu na cenówkę. labels_generated_at: artykuł był już w pliku ETIQUETTE, deleted_at: usunięty z listy, do przywrócenia.
create table price_markdowns (
    id                  uuid primary key default gen_random_uuid(),
    store_id            uuid          not null references stores (id),
    itm8                text          not null,
    ean                 text          not null,
    name                text,
    regular_price       numeric(12, 2),
    new_price           numeric(12, 2) not null,
    valid_from          date          not null,
    valid_to            date          not null,
    created_by          uuid references users (id) on delete set null,
    created_at          timestamptz   not null default now(),
    labels_generated_by uuid references users (id) on delete set null,
    labels_generated_at timestamptz,
    deleted_by          uuid references users (id) on delete set null,
    deleted_at          timestamptz
);

create index price_markdowns_store_idx on price_markdowns (store_id, created_at desc) where deleted_at is null;
