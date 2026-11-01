-- Marża wymagana na nomenklaturze sklepu (% od sprzedaży netto), do wprowadzania danych: marża artykułu poniżej
-- progu jest czerwona, a cena sugerowana to cena sprzedaży, przy której artykuł osiąga próg. Ustawia się ją na rejonie
-- ("01"), rodzinie ("0101") albo podrodzinie ("010101"); obowiązuje najbardziej szczegółowa. Pusta = bez progu
-- (wiersz zostaje, jak w region_budgets).
create table nomenclature_margins (
    id             uuid primary key default gen_random_uuid(),
    store_id       uuid         not null references stores (id),
    code           varchar(6)   not null,
    margin_percent numeric(5, 2),
    updated_by     uuid references users (id) on delete set null,
    updated_at     timestamptz  not null default now(),
    constraint nomenclature_margins_store_code_uq unique (store_id, code),
    constraint nomenclature_margins_code check (code ~ '^[0-9]{2}([0-9]{2}){0,2}$'),
    constraint nomenclature_margins_margin check (margin_percent >= 0 and margin_percent < 100)
);
