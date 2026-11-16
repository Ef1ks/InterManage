-- „Do pobrania” → skróty do folderów: skrót Windows (.lnk) do folderu na dysku sieciowym (np. raporty z Mercalys,
-- N:\AVISOCONFIG za zablokowanym dyskiem), który pracownik kładzie na pulpicie i wybiera w oknie wyboru folderu.
-- Nazwę nadaje właściciel (pobrany plik to <nazwa>.lnk), folder docelowy aplikacja odczytuje ze skrótu.
-- Usunięcie to znacznik; nazwa jest unikalna wśród aktywnych, bo „Pobierz wszystkie” pakuje je do jednego ZIP-a.
create table download_shortcuts (
    id          uuid primary key,
    name        varchar(100)  not null,
    target      varchar(1000),
    size_bytes  integer       not null,
    content     bytea         not null,
    created_by  uuid references users (id),
    created_at  timestamptz   not null,
    updated_at  timestamptz,
    removed_by  uuid references users (id),
    removed_at  timestamptz
);

create unique index download_shortcuts_name_uq on download_shortcuts (lower(name)) where removed_at is null;
