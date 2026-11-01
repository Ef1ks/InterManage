-- Wyszukiwanie artykułu po EAN-ie we wprowadzaniu danych porównuje EAN-y bez zer wiodących, a kody z etykiety wagi
-- po pierwszych 7 cyfrach; bez tych indeksów każde wyszukiwanie skanowało całą kartotekę i pary EAN.
create index products_store_ean_key_idx on products (store_id, ltrim(ean, '0'));
create index product_eans_ean_key_idx on product_eans (ltrim(ean, '0'));
create index products_store_scale_ean_idx on products (store_id, left(ean, 7))
    where ean ~ '^2[1-9][0-9]{5}00000[0-9]$';
