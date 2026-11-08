-- Podpisane polecenie albo wniosek o pracę w godzinach nadliczbowych (skan albo zdjęcie PDF, JPG, PNG, WEBP),
-- dołączony do decyzji z karty czasu pracy. Treść w bazie, jak pliki faktur spoza KSeF: plików jest mało i idą do kopii
-- zapasowej razem z resztą. Decyzja ma najwyżej jeden aktywny plik; wymiana i usunięcie to znacznik na poprzednim.
create table staff_time_decision_files (
    id           uuid primary key,
    decision_id  uuid         not null references staff_time_decisions (id),
    file_name    varchar(255) not null,
    content_type varchar(100) not null,
    size_bytes   integer      not null,
    content      bytea        not null,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz  not null,
    removed_by   uuid references users (id) on delete set null,
    removed_at   timestamptz
);

create index staff_time_decision_files_decision_idx on staff_time_decision_files (decision_id);
create unique index staff_time_decision_files_active_uq on staff_time_decision_files (decision_id)
    where removed_at is null;
