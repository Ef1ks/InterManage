-- Zapisy tymczasowe i transakcyjny zapis XML (data_entry_xml_exports z V57).
--
-- kind SAVED: "Zapisz XML". Wpis powstaje przy generowaniu pliku, ale do historii trafia dopiero, gdy przeglądarka
-- zapisze plik na dysku (saved_at); nieudany zapis zostawia wpis bez saved_at, którego nigdzie nie widać. Okno
-- wczytywania pokazuje 100 ostatnich zapisanych plików sklepu. Pliki zapisane przed tą migracją były w historii od
-- razu, więc dostają saved_at = created_at.
--
-- kind TEMPORARY: "Zapisz tymczasowo" (bez zapisu na dysku), najwyżej 10 w sklepie: starsze dostają znacznik
-- deleted_at/by. draft to cała tabela z przeglądarki (JSON), żeby wczytanie oddało ją dokładnie taką, jak była.
alter table data_entry_xml_exports
    add column kind       varchar(20) not null default 'SAVED',
    add column draft      text,
    add column saved_at   timestamptz,
    add column deleted_by uuid references users (id) on delete set null,
    add column deleted_at timestamptz;

alter table data_entry_xml_exports alter column kind drop default;

update data_entry_xml_exports set saved_at = created_at where saved_at is null;

drop index data_entry_xml_exports_store_idx;
create index data_entry_xml_exports_store_idx on data_entry_xml_exports (store_id, kind, created_at desc);
