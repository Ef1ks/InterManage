-- Czat firmowy. Rozmowa to czat całego sklepu (STORE: członkami są wszyscy z dostępem do sklepu, jeden na sklep),
-- grupa (GROUP: w sklepie albo niezależna od sklepu, store_id puste) albo rozmowa dwóch osób (DIRECT, jedna na parę,
-- bez sklepu). Nic nie znika: wiadomość cofnięta ma znacznik, a moderatorzy dalej widzą jej treść.
create table chat_conversations (
    id              uuid primary key,
    kind            text        not null check (kind in ('STORE', 'GROUP', 'DIRECT')),
    store_id        uuid references stores (id),
    name            text,
    -- Para kont rozmowy DIRECT (mniejsze id:większe id), żeby nie powstała druga rozmowa tych samych osób.
    direct_key      text,
    created_by      uuid references users (id) on delete set null,
    created_at      timestamptz not null,
    last_message_at timestamptz,
    deleted_by      uuid references users (id) on delete set null,
    deleted_at      timestamptz,
    check (kind <> 'STORE' or (store_id is not null and name is null)),
    check (kind <> 'DIRECT' or store_id is null),
    check ((kind = 'DIRECT') = (direct_key is not null))
);

create unique index chat_conversations_store_uq on chat_conversations (store_id) where kind = 'STORE';
create unique index chat_conversations_direct_uq on chat_conversations (direct_key) where kind = 'DIRECT';
create index chat_conversations_store_idx on chat_conversations (store_id) where deleted_at is null;

-- Członkowie grup i rozmów dwóch osób (czat sklepu liczy członków z dostępu do sklepu). Usunięcie z grupy to znacznik,
-- ponowne dodanie to nowy wiersz.
create table chat_members (
    id              uuid primary key,
    conversation_id uuid        not null references chat_conversations (id),
    user_id         uuid        not null references users (id),
    added_by        uuid references users (id) on delete set null,
    added_at        timestamptz not null,
    removed_by      uuid references users (id) on delete set null,
    removed_at      timestamptz
);

create unique index chat_members_active_uq on chat_members (conversation_id, user_id) where removed_at is null;
create index chat_members_user_idx on chat_members (user_id) where removed_at is null;

create table chat_messages (
    id              uuid primary key,
    conversation_id uuid        not null references chat_conversations (id),
    author_id       uuid references users (id) on delete set null,
    body            text        not null,
    created_at      timestamptz not null,
    -- Cofnięcie dla wszystkich: inni widzą „Wiadomość cofnięta”, moderatorzy treść na szaro.
    retracted_by    uuid references users (id) on delete set null,
    retracted_at    timestamptz
);

create index chat_messages_conversation_idx on chat_messages (conversation_id, created_at desc, id desc);

-- Załączniki. Dziś pliki (PDF, Excel…) z treścią w bazie. Kolumny dla zdjęć i filmów są od razu: wtedy treść leży
-- w prywatnym R2 (storage_key), w bazie zostaje tylko ThumbHash podglądu, a plik wygasa po tygodniu (expires_at).
create table chat_attachments (
    id           uuid primary key,
    message_id   uuid        not null references chat_messages (id),
    kind         text        not null check (kind in ('FILE', 'IMAGE', 'VIDEO')),
    file_name    text        not null,
    content_type text        not null,
    size_bytes   bigint      not null,
    content      bytea,
    storage_key  text,
    thumbhash    text,
    width        int,
    height       int,
    duration_ms  int,
    expires_at   timestamptz,
    created_at   timestamptz not null,
    check (content is not null or storage_key is not null)
);

create index chat_attachments_message_idx on chat_attachments (message_id);

-- Do kiedy konto przeczytało rozmowę (wiersz nadpisywany, to sam stan odczytu).
create table chat_read_states (
    conversation_id uuid        not null references chat_conversations (id),
    user_id         uuid        not null references users (id),
    last_read_at    timestamptz not null,
    primary key (conversation_id, user_id)
);

-- Ostatnia aktywność konta w aplikacji (przeglądarka i telefon zgłaszają się co minutę), wiersz nadpisywany.
create table user_presence (
    user_id      uuid primary key references users (id),
    last_seen_at timestamptz not null
);

-- Zarządzanie członkami i widok cofniętych wiadomości (CHAT:EDIT) domyślnie mają Dyrektor, Kierownik i jego zastępca
-- (Aderon ma wszystko); nowe sklepy dostają je ze StoreRoleDefaults.
insert into store_role_permissions (store_role_id, category, level)
select id, 'CHAT', 'EDIT' from store_roles
where role_key in ('DIRECTOR', 'MANAGER', 'DEPUTY_MANAGER') and deleted_at is null
on conflict do nothing;
