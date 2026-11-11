-- Zdjęcia kont w Cloudflare R2: plik leży w buckecie pod storage_key (publiczny adres na CDN), a w bazie zostaje
-- tylko opis. Starsze zdjęcia i zapisane bez połączenia z R2 (lokalnie, w testach) dalej mają treść w content.
alter table user_backgrounds add column storage_key varchar(200);
alter table user_backgrounds alter column content drop not null;
alter table user_backgrounds add constraint user_backgrounds_file_ck
    check (content is not null or storage_key is not null);

-- Zdjęcie profilowe konta. Obowiązuje jedno; nowe albo usunięte dostaje znacznik, a poprzednie zostaje do przywrócenia.
create table user_avatars (
    id           uuid primary key,
    user_id      uuid         not null references users (id),
    file_name    varchar(255) not null,
    content_type varchar(40)  not null,
    size_bytes   integer      not null,
    storage_key  varchar(200),
    content      bytea,
    created_at   timestamptz  not null,
    removed_by   uuid references users (id),
    removed_at   timestamptz,
    constraint user_avatars_file_ck check (content is not null or storage_key is not null)
);

create index user_avatars_user_idx on user_avatars (user_id);
create unique index user_avatars_current_uq on user_avatars (user_id) where removed_at is null;
