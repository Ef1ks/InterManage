-- Raport sprzedaży wygenerowany w ciągu dnia ma niepełną sprzedaż tego dnia (sklep jeszcze sprzedaje). Pełny jest
-- dopiero dzień zakończony: raport z 28.10 zawierający 27.10. Import nie zapisuje więc dnia, w którym raport
-- wygenerowano (ani późniejszych), tylko liczy go jako niezakończony, a kolejny raport dopisze ten dzień w całości.
alter table sales_imports
    add column incomplete_count integer not null default 0; -- wiersze z niezakończonego dnia, niezapisane

-- Niepełne dni zapisane, zanim import zaczął je pomijać. Sprzedaży nie usuwamy bezpowrotnie, więc przenosimy je
-- tutaj, a z tabeli sales znikają, żeby kolejny raport mógł dopisać te dni w pełnej wersji.
create table sales_incomplete (
    store_id         uuid           not null references stores (id),
    sale_date        date           not null,
    itm8             varchar(13)    not null,
    ean              varchar(14),
    quantity         numeric(14, 3) not null,
    cost_value       numeric(14, 2) not null,
    sale_value_gross numeric(14, 2) not null,
    vat_value        numeric(14, 2) not null,
    margin_value     numeric(14, 2) not null,
    margin_percent   numeric(9, 2),
    import_id        uuid           not null references sales_imports (id), -- import, który zapisał niepełny dzień
    moved_at         timestamptz    not null default now()
);

-- Zapisanej sprzedaży nie da się zmienić ani usunąć (sales_read_only); to jedyny wyjątek, na czas tej migracji.
alter table sales disable trigger sales_read_only;

with moved as (
    delete from sales s
    using sales_imports i
    where i.id = s.import_id
      and i.report_generated_at is not null
      and s.sale_date >= cast(i.report_generated_at as date)
    returning s.store_id, s.sale_date, s.itm8, s.ean, s.quantity, s.cost_value, s.sale_value_gross, s.vat_value,
              s.margin_value, s.margin_percent, s.import_id
)
insert into sales_incomplete (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                              margin_value, margin_percent, import_id)
select store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value, margin_value, margin_percent,
       import_id
from moved;

alter table sales enable trigger sales_read_only;

-- Liczba wierszy sklepu to suma created_count, więc przeniesione wiersze odejmujemy od importu, który je zapisał.
update sales_imports i
set created_count    = i.created_count - m.moved,
    incomplete_count = m.moved
from (select import_id, count(*) as moved from sales_incomplete group by import_id) m
where m.import_id = i.id;
