-- Ceny z plików TRACEART po EAN-ie: każda linia każdego pliku, także starszego niż kartoteka, bo products trzyma tylko
-- bieżącą cenę artykułu. Zmiany ceny liczy zapytanie (poprzednia linia tego EAN-u), więc kolejność wczytywania plików
-- nie ma znaczenia. EAN na 13 znakach; sale_price to cena regularna, promo_price cena promocyjna na kasie.
create table till_prices (
    store_id      uuid           not null references stores (id),
    ean           varchar(13)    not null,
    file_time     timestamp      not null,
    itm8          varchar(13)    not null,
    sale_price    numeric(12, 2) not null,
    promo_price   numeric(12, 2),
    promo_from    date,
    promo_to      date,
    price_file_id uuid           not null references price_file_imports (id),
    primary key (store_id, ean, file_time)
);

create index till_prices_itm8_idx on till_prices (store_id, itm8);

-- Pliki wczytane przed tą migracją trzeba przeczytać jeszcze raz: przeglądarka wysyła je sama (od 2026, jak zawsze),
-- a serwer bierze z nich tylko historię cen, bez zmiany cen w kartotece.
alter table price_file_imports add column prices_read boolean not null default false;
