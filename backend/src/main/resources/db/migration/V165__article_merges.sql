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

-- Połączeń nie liczymy w migracji: jedno zapytanie dla wszystkich sklepów dostawało zły plan i na produkcji liczyło
-- ponad 7 minut. Pustą tabelę wypełnia po starcie backend, osobno dla każdego sklepu (ArticleMergeBootstrap).
