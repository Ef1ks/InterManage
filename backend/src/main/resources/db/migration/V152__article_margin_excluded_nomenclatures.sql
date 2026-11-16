-- Nomenklatury (rejon, rodzina albo podrodzina), których artykułów raport „Artykuły z za niską marżą” nie pokazuje.
-- Lista wspólna dla sklepu, usunięcie z niej to znacznik.
create table article_margin_excluded_nomenclatures (
    id          uuid primary key,
    store_id    uuid         not null references stores (id),
    code        varchar(6)   not null check (code ~ '^([0-9]{2}){1,3}$'),
    created_by  uuid references users (id),
    created_at  timestamptz  not null,
    removed_by  uuid references users (id),
    removed_at  timestamptz
);

create unique index article_margin_excluded_nomenclatures_active_idx
    on article_margin_excluded_nomenclatures (store_id, code) where removed_at is null;
