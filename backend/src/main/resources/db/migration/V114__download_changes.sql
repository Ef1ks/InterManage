-- „Do pobrania”: lista zmian każdej wersji i powiadomienie pracowników o nowej wersji.

-- Czy nowa wersja ma pokazać pracownikom baner „Nowa wersja” (drobną poprawkę można wgrać po cichu).
alter table download_files add column announce boolean not null default true;

-- Co zmieniło się w wersji: dodano, zmieniono, naprawiono. Poprawka listy daje poprzednim wpisom znacznik.
create table download_file_changes (
    id          uuid primary key,
    file_id     uuid         not null references download_files (id),
    kind        varchar(20)  not null check (kind in ('ADDED', 'CHANGED', 'FIXED')),
    text        varchar(500) not null,
    position    integer      not null,
    created_by  uuid references users (id),
    created_at  timestamptz  not null,
    removed_by  uuid references users (id),
    removed_at  timestamptz
);

create index download_file_changes_file_idx on download_file_changes (file_id);

-- Kto widział wersję: pobrał ją albo potwierdził „Mam już tę wersję”. Bez wpisu konto dostaje baner.
create table download_file_seen (
    id             uuid primary key,
    file_id        uuid        not null references download_files (id),
    user_id        uuid        not null references users (id),
    seen_at        timestamptz not null,
    downloaded_at  timestamptz,
    unique (file_id, user_id)
);

-- Wersje wgrane przed tą zmianą nie są ogłaszane: wszyscy już je znają.
insert into download_file_seen (id, file_id, user_id, seen_at)
select gen_random_uuid(), f.id, u.id, now()
from download_files f cross join users u
where f.replaced_at is null;
