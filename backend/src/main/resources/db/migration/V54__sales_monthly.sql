-- Sumy sprzedaży artykułu (ITM8, ze wszystkich EAN-ów) w miesiącu: raporty za okres biorą pełne miesiące stąd,
-- a ze sprzedaży dziennej tylko dni z niepełnych miesięcy na brzegach okresu. Rok do roku to ok. 0,5 mln wierszy
-- miesięcznych zamiast ok. 2,6 mln dziennych.
--
-- Sprzedaż jest tylko dopisywana (sales_read_only blokuje zmiany i usuwanie), więc wyzwalacz po każdym INSERT
-- dodaje do sum miesiąca to, co weszło; sumy zawsze zgadzają się z sales. month to pierwszy dzień miesiąca.
create table sales_monthly (
    store_id         uuid           not null references stores (id),
    month            date           not null,
    itm8             varchar(13)    not null,
    quantity         numeric(16, 3) not null,
    cost_value       numeric(16, 2) not null,
    sale_value_gross numeric(16, 2) not null,
    sale_value_net   numeric(16, 2) not null,
    margin_value     numeric(16, 2) not null,
    primary key (store_id, month, itm8),
    constraint sales_monthly_first_day check (month = cast(date_trunc('month', cast(month as timestamp)) as date))
);

-- Grupa nomenklatury w raportach czyta sumy od artykułów z kartoteki.
create index sales_monthly_store_itm8_month_idx on sales_monthly (store_id, itm8, month);

insert into sales_monthly (store_id, month, itm8, quantity, cost_value, sale_value_gross, sale_value_net, margin_value)
select store_id, cast(date_trunc('month', cast(sale_date as timestamp)) as date), itm8,
       sum(quantity), sum(cost_value), sum(sale_value_gross), sum(sale_value_net), sum(margin_value)
from sales
group by 1, 2, 3;

create function sales_monthly_add() returns trigger language plpgsql as $$
begin
    insert into sales_monthly (store_id, month, itm8, quantity, cost_value, sale_value_gross, sale_value_net,
                               margin_value)
    select store_id, cast(date_trunc('month', cast(sale_date as timestamp)) as date), itm8,
           sum(quantity), sum(cost_value), sum(sale_value_gross), sum(sale_value_net), sum(margin_value)
    from inserted
    group by 1, 2, 3
    on conflict (store_id, month, itm8) do update set
        quantity         = sales_monthly.quantity + excluded.quantity,
        cost_value       = sales_monthly.cost_value + excluded.cost_value,
        sale_value_gross = sales_monthly.sale_value_gross + excluded.sale_value_gross,
        sale_value_net   = sales_monthly.sale_value_net + excluded.sale_value_net,
        margin_value     = sales_monthly.margin_value + excluded.margin_value;
    return null;
end
$$;

create trigger sales_monthly_add
    after insert on sales
    referencing new table as inserted
    for each statement execute function sales_monthly_add();
