-- Reguła dostawcy w oknie wczytywania faktur (data_entry_skipped_suppliers, V55): mode HIDDEN pomijany (jego faktur
-- nie ma w oknie), DONE odkładany (jego faktury są od razu w "Wczytane", bo sklep wprowadza tylko niektóre). Jedna
-- aktywna reguła na NIP; zmiana trybu to znacznik na poprzedniej regule. Wpisy sprzed tej migracji to pomijani.
alter table data_entry_skipped_suppliers
    add column mode varchar(20) not null default 'HIDDEN';
