-- Oznaczenia w wiadomościach czatu: @osoba, @wszyscy, @dział. Wiersz opisuje oznaczenie tak, jak stoi w treści
-- (label z chwili wysłania), a chat_mention_recipients to konta, które zostały nim oznaczone (bez autora).
create table chat_mentions (
    id            uuid primary key,
    message_id    uuid        not null references chat_messages (id),
    kind          text        not null check (kind in ('USER', 'ALL', 'DEPARTMENT')),
    user_id       uuid references users (id) on delete set null,
    department_id uuid references staff_departments (id),
    label         text        not null,
    created_at    timestamptz not null
);

create index chat_mentions_message_idx on chat_mentions (message_id);

create table chat_mention_recipients (
    message_id uuid not null references chat_messages (id),
    user_id    uuid not null references users (id) on delete cascade,
    primary key (message_id, user_id)
);

create index chat_mention_recipients_user_idx on chat_mention_recipients (user_id, message_id);
