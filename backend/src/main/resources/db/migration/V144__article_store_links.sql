-- Ten sam towar w dwóch sklepach: każdy sklep ma własne kody (lokalne ITM8 99…, inne EAN-y i nazwy), więc raporty
-- porównujące sklepy (raport lady, porównanie cen mięsa) pokazywały go w dwóch wierszach. Połączenie mówi, że artykuł
-- itm8 w sklepie store_id to ten sam towar co linked_itm8 w linked_store_id; działa w obie strony. Artykuł ma najwyżej
-- jedno połączenie z każdym innym sklepem. Odłączenie to znacznik.
create table article_store_links (
    id              uuid primary key,
    store_id        uuid        not null references stores (id),
    itm8            varchar(13) not null,
    linked_store_id uuid        not null references stores (id),
    linked_itm8     varchar(13) not null,
    created_by      uuid references users (id) on delete set null,
    created_at      timestamptz not null,
    deleted_by      uuid references users (id) on delete set null,
    deleted_at      timestamptz,
    check (store_id <> linked_store_id)
);
create unique index article_store_links_side_uq on article_store_links (store_id, itm8, linked_store_id)
    where deleted_at is null;
create unique index article_store_links_linked_side_uq on article_store_links (linked_store_id, linked_itm8, store_id)
    where deleted_at is null;
