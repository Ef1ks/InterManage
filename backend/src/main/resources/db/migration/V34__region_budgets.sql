-- Budżet rejonów nomenklatury sklepu do podsumowania sprzedaży i strat: ile ma wynosić marża (% od sprzedaży netto)
-- i ile najwyżej mogą wynieść straty (likwidacje, % od sprzedaży brutto). Pusta wartość = bez budżetu.
create table region_budgets (
    id               uuid primary key default gen_random_uuid(),
    store_id         uuid         not null references stores (id),
    region_code      varchar(2)   not null,
    margin_percent   numeric(5, 2),
    max_loss_percent numeric(5, 2),
    updated_by       uuid references users (id) on delete set null,
    updated_at       timestamptz  not null default now(),
    constraint region_budgets_store_region_uq unique (store_id, region_code),
    constraint region_budgets_code check (region_code ~ '^[0-9]{2}$'),
    constraint region_budgets_margin check (margin_percent between -100 and 100),
    constraint region_budgets_loss check (max_loss_percent between 0 and 100)
);
