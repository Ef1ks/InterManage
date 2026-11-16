-- Opisy gazetek pobiera w tle zadanie MEA z portalu (/api/cad/, lista gazetek z plikami): source_path i
-- source_updated_at to ścieżka i data zmiany pliku w portalu, po nich wiemy, że ten plik już mamy (także zastąpiony).
-- Wgrany ręcznie plik (zapas, gdy portal nie działa) ma je puste i uploaded_by. Osobna migracja, bo V150 była już na
-- produkcji bez tych kolumn.
alter table mea_leaflet_imports
    add column source_path       varchar(500),
    add column source_updated_at varchar(40);

create index mea_leaflet_imports_source on mea_leaflet_imports (source_path, source_updated_at);
