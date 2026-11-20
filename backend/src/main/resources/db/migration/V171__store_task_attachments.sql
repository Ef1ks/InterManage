-- Zrzuty ekranu i zdjęcia przy zadaniu (z dorysowanymi strzałkami, ramkami i napisami). Treść w bazie, nie w
-- publicznym R2: zrzuty pokazują sprzedaż i marże sklepu, więc oddaje je backend po sprawdzeniu dostępu do zadania.
-- Usunięcie to znacznik.
create table store_task_attachments (
    id           uuid primary key,
    task_id      uuid        not null references store_tasks (id),
    file_name    text        not null,
    content_type text        not null,
    size_bytes   int         not null,
    content      bytea       not null,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz not null,
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz
);

create index store_task_attachments_task_idx on store_task_attachments (task_id, created_at) where deleted_at is null;
