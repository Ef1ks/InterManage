-- Dostawcy pomijani w raporcie przyjęć za okres (np. przyjęcia, które księgowość rozlicza osobno). Po kodzie dostawcy
-- z linii dokumentów (supplier_code przyjęcia); nazwa to kopia do wyświetlania. Usunięcie z listy to znacznik.
create table delivery_report_excluded_suppliers (
    id            uuid primary key,
    store_id      uuid         not null references stores (id),
    supplier_code varchar(10)  not null,
    supplier_name varchar(200),
    created_by    uuid references users (id),
    created_at    timestamptz  not null,
    removed_by    uuid references users (id),
    removed_at    timestamptz
);

create unique index delivery_report_excluded_suppliers_active_idx
    on delivery_report_excluded_suppliers (store_id, supplier_code) where removed_at is null;

-- Pominięci dostawcy w chwili zamknięcia (opis do zamkniętego raportu), null = żadnych.
alter table delivery_report_closings add column excluded_suppliers text;
