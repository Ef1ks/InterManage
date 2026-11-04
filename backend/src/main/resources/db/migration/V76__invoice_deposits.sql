-- Kaucje z pozycji XML faktury (nazwa z "kaucj", 50 gr za sztukę): przyjęcie PZ ich nie ma, więc zgodność kwot
-- i porównanie z fakturą je pomijają. Puste = XML jeszcze nie odczytany (uzupełnia się przy kolejnym użyciu pliku).
alter table ksef_invoice_xml add column deposit_net numeric(16, 2);
