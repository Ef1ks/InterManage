-- Dostawców pomija już nie tylko raport za niskich marż, więc lista dostaje nazwę raportu (jak price_check_reviews).
alter table low_margin_excluded_suppliers rename to report_excluded_suppliers;

alter table report_excluded_suppliers add column report varchar(20) not null default 'LOW_MARGIN';
alter table report_excluded_suppliers alter column report drop default;

drop index low_margin_excluded_suppliers_active_idx;

create unique index report_excluded_suppliers_active_idx
    on report_excluded_suppliers (store_id, report, supplier_code) where removed_at is null;
