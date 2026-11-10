-- Lista cenówek ma dwa rodzaje wpisów: przecenę z okresem (MARKDOWN, cenówka promocyjna) i zwykłą zmianę ceny z pliku
-- CTRLPRIX we wprowadzaniu danych (PRICE_CHANGE, bez końca, biała cenówka). Plik ETIQUETTE powstaje osobno dla rodzaju.
alter table price_markdowns
    add column kind text not null default 'MARKDOWN' check (kind in ('MARKDOWN', 'PRICE_CHANGE')),
    add column price_control_file_id uuid references data_entry_price_control_files (id),
    alter column valid_to drop not null,
    add constraint price_markdowns_valid_to_check check (kind = 'PRICE_CHANGE' or valid_to is not null);
