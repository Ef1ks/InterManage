-- Cała sprzedaż artykułu (ITM8) z dnia, ze wszystkich EAN-ów (sales ma osobny wiersz dla każdej pary ITM8 + EAN).
-- Od tego zaczynają raporty sprzedaży. % marży nie da się zsumować, raport liczy go z sum.
create view sales_by_article as
select store_id, sale_date, itm8,
       sum(quantity)         as quantity,
       sum(cost_value)       as cost_value,
       sum(sale_value_gross) as sale_value_gross,
       sum(vat_value)        as vat_value,
       sum(sale_value_net)   as sale_value_net,
       sum(margin_value)     as margin_value
from sales
group by store_id, sale_date, itm8;
