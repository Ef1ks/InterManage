-- Strona „Do pobrania”: programy i pliki dla wszystkich pracowników (np. odtwarzacz makr AutoJob). Pozycję zakłada i
-- zmienia właściciel, pobiera każdy zalogowany. Usunięcie to znacznik.
create table downloads (
    id          uuid primary key,
    title       varchar(120)  not null,
    description varchar(2000),
    created_by  uuid references users (id),
    created_at  timestamptz   not null,
    updated_at  timestamptz,
    removed_by  uuid references users (id),
    removed_at  timestamptz
);

-- Wersje pliku pozycji. Treść w bazie, bo plików jest kilka (do 50 MB) i idą do kopii zapasowej razem z resztą.
-- Obowiązuje jedna wersja; nowa daje poprzedniej znacznik zastąpienia, a stara zostaje jako historia.
create table download_files (
    id           uuid primary key,
    download_id  uuid         not null references downloads (id),
    file_name    varchar(255) not null,
    version      varchar(40),
    size_bytes   integer      not null,
    sha256       varchar(64)  not null,
    content      bytea        not null,
    created_by   uuid references users (id),
    created_at   timestamptz  not null,
    replaced_by  uuid references users (id),
    replaced_at  timestamptz
);

create index download_files_download_idx on download_files (download_id);
create unique index download_files_current_uq on download_files (download_id) where replaced_at is null;
