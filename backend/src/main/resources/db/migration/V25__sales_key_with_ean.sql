-- Ten sam artykuł (ITM8) sprzedaje się w jednym dniu pod kilkoma EAN-ami, a raport ma osobny wiersz dla każdej pary
-- ITM8 + EAN. Wiersz sprzedaży to więc sklep + dzień + ITM8 + EAN. EAN bywa pusty, dlatego unikalność zamiast klucza
-- głównego, z pustym EAN-em liczonym jako jedna wartość (nulls not distinct). Ten sam indeks dalej obsługuje raporty
-- za okres po (sklep, data).
alter table sales drop constraint sales_pkey;
alter table sales add constraint sales_key unique nulls not distinct (store_id, sale_date, itm8, ean);
