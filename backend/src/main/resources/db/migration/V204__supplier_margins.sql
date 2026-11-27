-- Stała marża dostawcy (Dostawcy i wprowadzanie danych): marża % od sprzedaży brutto, z którą sklep sprzedaje
-- artykuły tego dostawcy. Wiersz bez nomenklatury to marża domyślna dostawcy, wiersz z kodem rejonu (2 cyfry),
-- rodziny (4) albo podrodziny (6) to wyjątek dla tej części nomenklatury; najbardziej szczegółowy wygrywa.
-- We wprowadzaniu danych zastępuje marżę wymaganą na nomenklaturze przy dokumentach tego dostawcy (cena
-- sugerowana, ostrzeżenie o za małej marży). Zmiana i usunięcie to znacznik deleted_at/by.
create table supplier_margins (
    id                uuid primary key default gen_random_uuid(),
    store_id          uuid         not null references stores (id),
    supplier_id       uuid         not null references suppliers (id),
    nomenclature_code varchar(6),
    margin_percent    numeric(5, 2) not null,
    created_by        uuid references users (id) on delete set null,
    created_at        timestamptz  not null default now(),
    deleted_by        uuid references users (id) on delete set null,
    deleted_at        timestamptz,
    constraint supplier_margins_percent check (margin_percent >= 0 and margin_percent < 100),
    constraint supplier_margins_code check (nomenclature_code is null or nomenclature_code ~ '^(\d{2}|\d{4}|\d{6})$')
);

create unique index supplier_margins_active
    on supplier_margins (supplier_id, coalesce(nomenclature_code, '')) where deleted_at is null;
create index supplier_margins_store on supplier_margins (store_id) where deleted_at is null;
