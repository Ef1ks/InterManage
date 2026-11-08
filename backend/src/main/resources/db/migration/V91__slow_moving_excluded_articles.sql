-- Artykuły pominięte w raporcie niesprzedających się (np. towar sezonowy albo trzymany celowo). Artykuł należy do sklepu,
-- więc lista jest osobna dla każdego sklepu. Przywrócenie do raportu to znacznik; ponowne pominięcie to nowy wpis.
create table slow_moving_excluded_articles (
    id          uuid primary key,
    product_id  uuid        not null references products (id),
    excluded_by uuid references users (id) on delete set null,
    excluded_at timestamptz not null,
    removed_by  uuid references users (id) on delete set null,
    removed_at  timestamptz
);

create unique index slow_moving_excluded_articles_uq on slow_moving_excluded_articles (product_id)
    where removed_at is null;
