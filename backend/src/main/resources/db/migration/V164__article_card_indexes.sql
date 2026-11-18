-- Karta artykułu (dostawy, protokoły) szuka linii dokumentów także po EAN-ie bez zer wiodących (towar pod lokalnym
-- ITM8 dostawcy), a dostawy na gazetkę po ITM8 równoległym i EAN-ie z formularzy MEA. Bez indeksów na tych wyrażeniach
-- każde otwarcie karty przeglądało całe document_lines (ok. 0,6 s) i mea_document_lines (ok. 70 ms przy każdej
-- dostawie), razem ok. 4 s. Wyrażenia muszą być takie same jak w zapytaniach DocumentLineRepository.
create index document_lines_ean_plain on document_lines (ltrim(btrim(ean), '0'));
create index mea_document_lines_parallel_plain on mea_document_lines (ltrim(parallel_itm8, '0'));
create index mea_document_lines_ean_plain on mea_document_lines (ltrim(btrim(ean), '0'));
