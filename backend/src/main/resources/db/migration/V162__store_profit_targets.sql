-- Roczny cel zysku sklepu (ustawienia raportu „Podsumowanie sprzedaży i strat”): kwota marży i marży po stratach
-- (likwidacje), którą sklep chce osiągnąć w roku kalendarzowym. Raport pokazuje, ile z niej osiągnięto od 1 stycznia
-- do końca wybranego okresu. Puste = bez celu. Zmiana i usunięcie to znacznik deleted_at/by na poprzednim wpisie,
-- aktywny jest najwyżej jeden na sklep i rok.
create table store_profit_targets (
    id                        uuid primary key default gen_random_uuid(),
    store_id                  uuid           not null references stores (id),
    target_year               integer        not null,
    margin_value              numeric(14, 2),
    margin_after_losses_value numeric(14, 2),
    created_by                uuid references users (id) on delete set null,
    created_at                timestamptz    not null default now(),
    deleted_by                uuid references users (id) on delete set null,
    deleted_at                timestamptz,
    constraint store_profit_targets_year check (target_year between 2000 and 2100),
    constraint store_profit_targets_margin check (margin_value > 0),
    constraint store_profit_targets_after_losses check (margin_after_losses_value > 0),
    constraint store_profit_targets_any check (margin_value is not null or margin_after_losses_value is not null)
);

create unique index store_profit_targets_active on store_profit_targets (store_id, target_year)
    where deleted_at is null;
