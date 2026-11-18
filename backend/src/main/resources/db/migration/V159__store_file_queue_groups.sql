-- Kolejka plików sklepu: pliki jednego dokumentu (faktura z protokołami, nowymi artykułami, zmianą cen…) są w oknie
-- kolejki jednym wierszem. group_key to klucz dokumentu nadany w przeglądarce (np. faktura KSeF), group_label jego
-- opis (numer i dostawca). Plik bez dokumentu (sama zmiana cen, protokół, etykiety) ma puste i jest osobnym wierszem.
alter table store_file_queue
    add column group_key   varchar(200),
    add column group_label varchar(300);
