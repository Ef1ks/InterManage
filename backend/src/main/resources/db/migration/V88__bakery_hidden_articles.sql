-- Artykuły dostawcy, których domyślnie nie ma na liście WZ w weryfikacji piekarni (np. przyjeżdżają tylko sezonowo).
-- Lista jest osobna dla każdego dostawcy, a dostawca należy do sklepu. Trzymamy odznaczone, nie zaznaczone, więc nowy
-- artykuł z kartoteki sam trafia na WZ. Ponowne zaznaczenie to znacznik.
create table bakery_hidden_articles (
    id          uuid primary key,
    supplier_id uuid        not null references suppliers (id),
    product_id  uuid        not null references products (id),
    hidden_by   uuid references users (id) on delete set null,
    hidden_at   timestamptz not null,
    removed_by  uuid references users (id) on delete set null,
    removed_at  timestamptz
);

create unique index bakery_hidden_articles_uq on bakery_hidden_articles (supplier_id, product_id)
    where removed_at is null;
