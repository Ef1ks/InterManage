-- Open Food Facts: wyciąg z bazy produktów (cały świat, ok. 4,6 mln EAN-ów) jako wskazówka dla "Uzupełnij z AI".
-- Dane są wspólne dla wszystkich sklepów. Ładuje je skrypt tools/openfoodfacts (wyciąg z pliku Parquet + psql),
-- nie aplikacja: plik jest za duży na wgrywanie przez stronę.

create table off_imports (
    id uuid primary key default gen_random_uuid(),
    file_name text not null,
    total_rows integer not null,
    created_count integer not null,
    updated_count integer not null,
    created_at timestamp with time zone not null default now()
);

-- Kluczem jest EAN bez zer wiodących (jak przy porównaniach EAN-ów we wprowadzaniu danych). Nazwy: główna z OFF,
-- polska i angielska tylko, gdy różnią się od głównej. Kategorie to najbardziej szczegółowe kategorie OFF tekstem.
-- Produkty, których zabrakło w nowszym pliku, zostają (last_import_id wskazuje ostatni plik, w którym były).
create table off_products (
    ean varchar(14) primary key,
    name text,
    name_pl text,
    name_en text,
    brands text,
    quantity text,
    categories text,
    modified_at timestamp with time zone,
    last_import_id uuid not null references off_imports (id)
);

-- Czy dla pozycji był produkt w Open Food Facts (null: propozycja sprzed OFF).
alter table data_entry_suggestions add column off_found boolean;
