-- Dostawcy do kontroli strat w raporcie protokołów: sklep zakłada grupę (zwykle dostawcę albo producenta) z kolorem
-- z palety i podpina do niej artykuły, bo nazwy artykułów bywają różne i nie da się ich znaleźć jednym wyszukaniem.
-- Artykuły po ITM8 (w protokołach bywają artykuły spoza kartoteki). Usunięcie grupy i odpięcie artykułu to znacznik.
create table loss_control_groups (
    id         uuid primary key,
    store_id   uuid        not null references stores (id),
    name       text        not null,
    color      text        not null,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create unique index loss_control_groups_name_uq on loss_control_groups (store_id, lower(name))
    where deleted_at is null;

create table loss_control_group_articles (
    id         uuid primary key,
    group_id   uuid        not null references loss_control_groups (id),
    itm8       text        not null,
    added_by   uuid references users (id) on delete set null,
    added_at   timestamptz not null,
    removed_by uuid references users (id) on delete set null,
    removed_at timestamptz
);

create unique index loss_control_group_articles_uq on loss_control_group_articles (group_id, itm8)
    where removed_at is null;
