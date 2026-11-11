-- Własne zdjęcie w tle aplikacji, osobno dla każdego konta (idzie za użytkownikiem na każdy komputer).
-- Obowiązuje jedno; nowe albo usunięte dostaje znacznik, a poprzednie zostaje do przywrócenia.
create table user_backgrounds (
    id           uuid primary key,
    user_id      uuid        not null references users (id),
    file_name    varchar(255) not null,
    content_type varchar(40) not null,
    size_bytes   integer     not null,
    content      bytea       not null,
    -- Przyciemnienie (w ciemnym motywie) albo rozjaśnienie zdjęcia pod treścią, w procentach, żeby tekst był czytelny.
    veil_percent smallint    not null default 70 check (veil_percent between 0 and 95),
    created_at   timestamptz not null,
    removed_by   uuid references users (id),
    removed_at   timestamptz
);

create index user_backgrounds_user_idx on user_backgrounds (user_id);
create unique index user_backgrounds_current_uq on user_backgrounds (user_id) where removed_at is null;
