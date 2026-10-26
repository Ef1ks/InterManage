-- Własna cena sprzedaży wyrobu, gdy wyliczona (koszt + narzut + VAT) się nie podoba. Do eksportu idzie własna cena,
-- jeśli jest. Przy "Odśwież ceny" kalkulacje z własną ceną trafiają na listę do akceptacji (zostawić własną
-- czy przyjąć nową wyliczoną); "zostaw" przy cenie wyliczonej, która skoczyła o 10% i więcej, zapisuje ją tutaj.
alter table price_calculations
    add column manual_price        numeric(10, 2) check (manual_price > 0),
    add column manual_price_set_by uuid references users (id) on delete set null,
    add column manual_price_set_at timestamptz;
