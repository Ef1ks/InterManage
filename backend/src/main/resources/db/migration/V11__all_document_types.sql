-- Wszystkie typy dokumentów z raportu Wejścia/Wyjścia mają te same kolumny, więc zamiast tabel na każdy typ
-- jest jedna para tabel z kolumną typu (PZ, PK, WZ, PL, PPP, ZW, RWPO, RWREP - lista w DocumentType.java).
-- Zapisane dotąd przyjęcia PZ przechodzą bez zmian.

alter table pz_documents rename to documents;
alter index pz_documents_pkey rename to documents_pkey;
alter table documents add column type varchar(10) not null default 'PZ';
alter table documents alter column type drop default;
drop index pz_documents_store_number_uq;
drop index pz_documents_store_date_idx;
-- Numer jest unikalny w obrębie typu (likwidacje i przeniesienia mają własną numerację).
create unique index documents_store_type_number_uq on documents (store_id, type, number);
create index documents_store_type_date_idx on documents (store_id, type, document_date desc);
-- Dostawca w nagłówku tylko wtedy, gdy jest wspólny dla wszystkich linii (w przyjęciach, korektach, zwrotach).
-- W likwidacjach czy przeniesieniach każda linia ma swojego dostawcę.
comment on column documents.supplier_code is 'Wspólny dostawca wszystkich linii; pusty, gdy linie mają różnych';

alter table pz_document_lines rename to document_lines;
alter index pz_document_lines_pkey rename to document_lines_pkey;
alter index pz_document_lines_itm8_idx rename to document_lines_itm8_idx;
alter table document_lines
    add column supplier_code varchar(10),  -- Code fournisseur
    add column supplier_name varchar(100); -- Libellé fournisseur
update document_lines l set supplier_code = d.supplier_code, supplier_name = d.supplier_name
from documents d where d.id = l.document_id;
-- Zwroty prasy mają wszystkie linie z N° ligne = 1, więc linię wyznacza numer razem z ITM8.
drop index pz_document_lines_document_line_uq;
create unique index document_lines_document_line_uq on document_lines (document_id, line_number, itm8);

alter table document_imports rename column pz_lines to saved_lines;
alter table document_imports rename column pz_created_count to created_count;
alter table document_imports rename column pz_updated_count to updated_count;
