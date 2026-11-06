-- Marża docelowa kalkulacji: procent doliczany do kosztu zamiast standardowych 60%; pusta = standardowa.
alter table price_calculations
    add column target_markup_percent numeric(5, 2),
    add constraint price_calculations_target_markup_ck check (target_markup_percent > 0);
