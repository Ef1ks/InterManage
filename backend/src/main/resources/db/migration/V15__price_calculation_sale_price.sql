-- Kalkulacja liczy się dla gotowego wyrobu z kartoteki sklepu (nomenklatury 24, 36, 46) i kończy ceną sprzedaży:
-- koszt 1 kg + 60% narzutu + VAT (domyślnie 5%), zaokrąglone w górę do ,99 zł. Z wyrobu bierzemy nazwę i EAN do eksportu.
alter table price_calculations
    add column product_id          uuid references products (id),
    add column vat_rate            smallint not null default 5,
    -- Kiedy ostatnio przeliczono ceny wszystkich składników przyciskiem "Odśwież ceny".
    add column prices_refreshed_at timestamptz;

-- Jeden wyrób = jedna kalkulacja (eksport po EAN nie może mieć dwóch cen tego samego produktu).
create unique index price_calculations_product_uq on price_calculations (store_id, product_id)
    where deleted_at is null and product_id is not null;

-- Cena składnika jest teraz zapamiętywana: z ostatniej dostawy (linia przyjęcia PZ), a gdy produktu nie było
-- w dostawach - z kartoteki. Zmienia ją dopiero "Odśwież ceny", więc kalkulacja nie skacze po każdym imporcie.
alter table price_calculation_items
    add column unit_cost         numeric(14, 4),
    add column price_source      varchar(10), -- DELIVERY albo CATALOG; pusty, gdy nigdzie nie ma ceny
    add column price_document_id uuid references documents (id),
    add column priced_at         timestamptz;

update price_calculation_items i
set unit_cost    = round(p.purchase_price / case when p.net_content > 0 then p.net_content else 1 end, 4),
    price_source = 'CATALOG',
    priced_at    = now()
from products p
where p.id = i.product_id and p.purchase_price > 0;
