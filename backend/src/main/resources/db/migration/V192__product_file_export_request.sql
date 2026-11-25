-- Artykuły wydanego pliku (JSON żądania): po wczytaniu pliku makrem serwer sam dodaje nowe artykuły do bazy, bez pytania
-- pracownika. Pliki wydane wcześniej go nie mają.
alter table product_file_exports add column request text;
