-- Pilnowanie wgrania zmian cen (CTRLPRIX): program sklepu po wczytaniu pliku zmienia jego końcówkę z .traite0 na .Traite1,
-- a każde kolejne przetworzenie podnosi liczbę (.Traite2, .Traite3). Przeglądarka sprawdza folder plików PROTO i zapisuje,
-- że plik jest wgrany (loaded_at) i ile razy go przetworzono (processed_count, puste przy ręcznym oznaczeniu, loaded_manually).
-- Usunięcie z listy to znacznik (deleted_at/by), jak wszędzie.
alter table data_entry_price_control_files
    add column loaded_at       timestamptz,
    add column loaded_by       uuid references users (id) on delete set null,
    add column loaded_manually boolean not null default false,
    add column processed_count integer,
    add column deleted_at      timestamptz,
    add column deleted_by      uuid references users (id) on delete set null;

-- Pliki sprzed pilnowania: czekają tylko te z ostatniego tygodnia (folder je potwierdzi), starsze uznajemy za wgrane,
-- żeby lista niewgranych nie zaczęła się od całej historii.
update data_entry_price_control_files
set loaded_at = created_at
where created_at < now() - interval '7 days';

create index data_entry_price_control_files_pending_idx
    on data_entry_price_control_files (store_id, created_at)
    where loaded_at is null and deleted_at is null;
