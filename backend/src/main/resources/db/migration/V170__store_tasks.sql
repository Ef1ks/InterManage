-- Zadania sklepu: właściciel zleca kierownikowi sprawę (zwykle z raportu: ujemne stany, braki, marże), kierownik ją
-- odhacza, a właściciel przyjmuje albo odsyła do poprawy. Zadanie ma opcjonalną listę pozycji (artykuły z danymi
-- z chwili zlecenia, bo raport do jutra się zmieni) i adres widoku raportu, z którego powstało.
-- Usunięcie to znacznik, a historia zmian (store_task_events) jest tylko dopisywana.
create table store_tasks (
    id            uuid primary key,
    store_id      uuid        not null references stores (id),
    title         text        not null,
    description   text,
    due_date      date,
    priority      text        not null,
    status        text        not null,
    assignee_id   uuid        not null references users (id),
    created_by    uuid references users (id) on delete set null,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    source_kind   text,
    source_label  text,
    source_url    text,
    done_by       uuid references users (id) on delete set null,
    done_at       timestamptz,
    done_comment  text,
    accepted_by   uuid references users (id) on delete set null,
    accepted_at   timestamptz,
    return_comment text,
    deleted_by    uuid references users (id) on delete set null,
    deleted_at    timestamptz,
    constraint store_tasks_priority_ck check (priority in ('NORMAL', 'URGENT')),
    constraint store_tasks_status_ck check (status in ('OPEN', 'DONE', 'ACCEPTED'))
);

create index store_tasks_assignee_idx on store_tasks (store_id, assignee_id, status) where deleted_at is null;
create index store_tasks_store_idx on store_tasks (store_id, created_at desc) where deleted_at is null;

create table store_task_items (
    id          uuid primary key,
    task_id     uuid        not null references store_tasks (id),
    position    int         not null,
    itm8        text,
    ean         text,
    name        text        not null,
    details     text,
    checked_by  uuid references users (id) on delete set null,
    checked_at  timestamptz,
    note        text
);

create index store_task_items_task_idx on store_task_items (task_id, position);

create table store_task_events (
    id         uuid primary key,
    task_id    uuid        not null references store_tasks (id),
    kind       text        not null,
    user_id    uuid references users (id) on delete set null,
    at         timestamptz not null,
    comment    text
);

create index store_task_events_task_idx on store_task_events (task_id, at);
