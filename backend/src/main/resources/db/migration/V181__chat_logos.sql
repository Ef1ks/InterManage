-- Logo czatu sklepu albo grupy (zmienia zarządzający czatem). Obowiązuje jedno na rozmowę; wymiana i usunięcie to
-- znacznik, więc poprzednie da się przywrócić.
create table chat_conversation_logos (
    id              uuid primary key,
    conversation_id uuid        not null references chat_conversations (id),
    content_type    text        not null,
    content         bytea       not null,
    created_by      uuid references users (id) on delete set null,
    created_at      timestamptz not null,
    deleted_by      uuid references users (id) on delete set null,
    deleted_at      timestamptz
);

-- Czat sklepu może mieć własną nazwę (pusta = „Cały sklep <numer>”): zostaje tylko wymóg sklepu.
alter table chat_conversations drop constraint chat_conversations_check;
alter table chat_conversations add constraint chat_conversations_store_check check (kind <> 'STORE' or store_id is not null);

create unique index chat_conversation_logos_active_uq on chat_conversation_logos (conversation_id)
    where deleted_at is null;
