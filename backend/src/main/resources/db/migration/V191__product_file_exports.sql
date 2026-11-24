-- Plik nowych i zmienionych artykułów (CADBEZP) oddany przeglądarce: numer dostawcy w programie sklepu i stawki VAT
-- artykułów, których potrzebuje makro wczytujące plik (sam plik numeru dostawcy nie ma). Plik zapisany przez agenta
-- rozpoznajemy po nazwie i SHA-256 treści.
create table product_file_exports (
    id uuid primary key,
    store_id uuid not null references stores (id),
    file_name varchar(255) not null,
    content_sha256 varchar(64) not null,
    -- Kod dostawcy w programie sklepu; pusty = nieznany, pliku nie da się wczytać makrem.
    supplier_code varchar(20),
    -- EAN;procent VAT każdego artykułu z EAN-em.
    articles text[] not null,
    created_at timestamptz not null
);

create index product_file_exports_content on product_file_exports (store_id, content_sha256);

alter table store_file_queue add column product_file_export_id uuid references product_file_exports (id);
