-- Cena artykułu (ITM8) w miesiącu: 75. percentyl cen dnia (sprzedaż brutto / ilość) i liczba dni ze sprzedażą. Z niej
-- raporty rozpoznają promocje, a liczenie percentyli z ok. 650 tys. dni sprzedaży przy każdym otwarciu raportu trwało
-- 2-3 s. Percentyla nie da się dopisywać jak sum w sales_monthly, więc import sprzedaży przelicza swoje miesiące
-- (SaleRepository.refreshMonthPrices). month to pierwszy dzień miesiąca.
create table sales_month_prices (
    store_id  uuid             not null references stores (id),
    itm8      varchar(13)      not null,
    month     date             not null,
    price     double precision not null,
    sale_days integer          not null,
    primary key (store_id, month, itm8),
    constraint sales_month_prices_first_day check (month = cast(date_trunc('month', cast(month as timestamp)) as date))
);

insert into sales_month_prices (store_id, itm8, month, price, sale_days)
select s.store_id, s.itm8, cast(date_trunc('month', s.sale_date) as date),
       percentile_cont(0.75) within group (order by s.sale_value_gross / s.quantity), count(*)
from sales_by_article s
where s.quantity > 0 and s.sale_value_gross > 0
group by 1, 2, 3;
