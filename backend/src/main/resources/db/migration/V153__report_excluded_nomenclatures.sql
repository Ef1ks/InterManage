-- Pomijane nomenklatury ma już nie tylko raport artykułów z za niską marżą, ale każdy raport weryfikacji zakupów,
-- więc lista dostaje nazwę raportu (jak report_excluded_suppliers).
alter table article_margin_excluded_nomenclatures rename to report_excluded_nomenclatures;

alter table report_excluded_nomenclatures add column report varchar(20) not null default 'ARTICLE_MARGIN';
alter table report_excluded_nomenclatures alter column report drop default;

drop index article_margin_excluded_nomenclatures_active_idx;

create unique index report_excluded_nomenclatures_active_idx
    on report_excluded_nomenclatures (store_id, report, code) where removed_at is null;
