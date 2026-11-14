-- Wycofanie wgrania listy promocji innego sklepu.
--
-- 14.11.2026 o 10:49 do sklepu 10023 trafiła lista promocji sklepu 10025 (promocje.pdf, import
-- 01a1d975-ec15-7ad2-8ef8-b450edd155c4): parser nie odczytał numeru sklepu, więc sprawdzenie sklepu zostało
-- pominięte. Lokalne ITM8 99… są w każdym sklepie inne, więc część promocji trafiła na inne artykuły (np. promocja
-- METALOWYCH HACZYKÓW 3,99 zł do 31.12 na FLAKACH WOŁOWYCH 99015748).
--
-- Promocje, które dodał tylko ten plik, przechodzą do withdrawn_promotions (zostają w bazie razem z powodem, więc da
-- się je przywrócić), a samo wgranie dostaje znacznik wycofania. Na innych bazach (testy, lokalnie) tego importu nie
-- ma i migracja niczego nie zmienia.

alter table promotion_imports
    add column withdrawn_at timestamptz,
    add column withdrawn_reason text;

create table withdrawn_promotions (
    id                     uuid primary key,
    store_id               uuid           not null references stores (id),
    itm8                   varchar(13)    not null,
    ean                    varchar(14),
    name                   varchar(100)   not null,
    start_date             date           not null,
    end_date               date           not null,
    catalog_purchase_price numeric(12, 2),
    regular_sale_price     numeric(12, 2),
    promo_price            numeric(12, 2),
    margin_percent         numeric(10, 2),
    first_import_id        uuid           not null references promotion_imports (id),
    last_import_id         uuid           not null references promotion_imports (id),
    withdrawn_import_id    uuid           not null references promotion_imports (id),
    withdrawn_at           timestamptz    not null default now(),
    withdrawn_reason       text           not null
);

create index withdrawn_promotions_import_idx on withdrawn_promotions (withdrawn_import_id);

update promotion_imports
set withdrawn_at = now(),
    withdrawn_reason = 'Lista promocji sklepu 10025 wgrana przez pomyłkę do sklepu 10023'
where id = '01a1d975-ec15-7ad2-8ef8-b450edd155c4';

-- Promocje dodane tylko przez ten plik (żaden późniejszy raport 10023 ich nie potwierdził).
with moved as (
    delete from promotions p
    using promotion_imports i
    where i.id = '01a1d975-ec15-7ad2-8ef8-b450edd155c4'
      and p.store_id = i.store_id
      and p.first_import_id = i.id
      and p.last_import_id = i.id
    returning p.*
)
insert into withdrawn_promotions (id, store_id, itm8, ean, name, start_date, end_date, catalog_purchase_price,
                                  regular_sale_price, promo_price, margin_percent, first_import_id, last_import_id,
                                  withdrawn_import_id, withdrawn_reason)
select id, store_id, itm8, ean, name, start_date, end_date, catalog_purchase_price, regular_sale_price, promo_price,
       margin_percent, first_import_id, last_import_id, '01a1d975-ec15-7ad2-8ef8-b450edd155c4',
       'Lista promocji sklepu 10025 wgrana przez pomyłkę do sklepu 10023'
from moved;

-- Promocja dodana przez ten plik, którą potwierdził późniejszy raport 10023, jest promocją sklepu: pierwszym
-- wgraniem staje się to, które ją potwierdziło.
update promotions p
set first_import_id = p.last_import_id
where p.first_import_id = '01a1d975-ec15-7ad2-8ef8-b450edd155c4'
  and p.last_import_id <> p.first_import_id;

-- Promocje 10023 z wcześniejszych list, które ten plik tylko nadpisał, a których nie było już w porannej liście
-- 10023 z 14.11 (odwołane): ostatnim wgraniem wraca ostatnia lista 10023 sprzed tego dnia.
update promotions p
set last_import_id = (
    select l.id
    from promotion_imports l
    where l.store_id = p.store_id
      and l.withdrawn_at is null
      and l.imported_at < '2026-11-14 00:00:00+01'
    order by l.imported_at desc
    limit 1)
where p.last_import_id = '01a1d975-ec15-7ad2-8ef8-b450edd155c4'
  and p.first_import_id <> p.last_import_id
  and exists (select 1
              from promotion_imports l
              where l.store_id = p.store_id
                and l.withdrawn_at is null
                and l.imported_at < '2026-11-14 00:00:00+01');
