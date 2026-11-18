-- Artykuł usunięty w Mercalys, którego EAN przeszedł na inny artykuł jako multikod (albo główny EAN), staje się jego
-- częścią: karta artykułu liczy sprzedaż, dostawy i protokoły głównego ITM8 razem z połączonymi. Połączenie wynika
-- z kartoteki i multikodów, więc odświeża je każdy import obu raportów (ArticleMergeRepository); połączenie, które
-- przestało obowiązywać, dostaje znacznik ended_at. alias_itm8: artykuł spoza najnowszego raportu kartoteki,
-- main_itm8: artykuł z najnowszego raportu, do którego należy teraz jego EAN (ean, bez zer wiodących).
create table article_merges (
    id         uuid primary key,
    store_id   uuid        not null references stores (id),
    alias_itm8 varchar(13) not null,
    main_itm8  varchar(13) not null,
    ean        varchar(14) not null,
    created_at timestamptz not null,
    ended_at   timestamptz,
    check (alias_itm8 <> main_itm8)
);
create unique index article_merges_alias_uq on article_merges (store_id, alias_itm8) where ended_at is null;
create index article_merges_main_idx on article_merges (store_id, main_itm8) where ended_at is null;

insert into article_merges (id, store_id, alias_itm8, main_itm8, ean, created_at)
with latest as (
    select distinct on (store_id) store_id, id from product_imports order by store_id, report_generated_at desc
), gone as (
    select p.store_id, p.itm8, ltrim(btrim(e.ean), '0') as ean
    from products p
    join latest l on l.store_id = p.store_id and p.last_seen_import_id <> l.id
    cross join lateral (select p.ean union select pe.ean from product_eans pe where pe.product_id = p.id) e
    where ltrim(btrim(e.ean), '0') <> ''
), owners as (
    select c.store_id, c.itm8, ltrim(btrim(c.ean), '0') as ean from product_codes c where c.current
    union
    select p.store_id, p.itm8, ltrim(btrim(p.ean), '0') from products p
    join latest l on l.store_id = p.store_id and p.last_seen_import_id = l.id
), pairs as (
    select g.store_id, g.itm8 as alias_itm8, o.itm8 as main_itm8, min(g.ean) as ean
    from gone g
    join owners o on o.store_id = g.store_id and o.ean = g.ean and o.itm8 <> g.itm8
    join products m on m.store_id = o.store_id and m.itm8 = o.itm8
    join latest l on l.store_id = m.store_id and m.last_seen_import_id = l.id
    group by g.store_id, g.itm8, o.itm8
)
select gen_random_uuid(), p.store_id, p.alias_itm8, p.main_itm8, p.ean, now()
from pairs p
where not exists (select 1 from pairs q
                  where q.store_id = p.store_id and q.alias_itm8 = p.alias_itm8 and q.main_itm8 <> p.main_itm8);
