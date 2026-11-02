-- Folder pliku nowych i zmienionych artykułów (CADBEZP .xls), który powstaje przy zapisie pliku XML we wprowadzaniu
-- danych i trafia do innego programu niż pliki PROTO. Jak przy PROTO: tylko podpowiedź przy wyborze folderu w
-- przeglądarce, pusty = bez podpowiedzi.
alter table store_proto_settings add column product_file_folder text;
