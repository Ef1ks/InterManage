-- Układ grafiku: własne grupy pracowników i kolejność wyświetlania, osobno dla działu w każdym sklepie. Dotyczy tylko
-- wyglądu, więc nie wchodzi do opublikowanych wersji.
create table staff_schedule_groups (
    id            uuid primary key,
    store_id      uuid         not null references stores (id),
    department_id uuid         not null references staff_departments (id),
    name          varchar(60)  not null,
    position      integer      not null,
    created_by    uuid references users (id) on delete set null,
    created_at    timestamptz  not null,
    deleted_by    uuid references users (id) on delete set null,
    deleted_at    timestamptz
);

create index staff_schedule_groups_department_idx on staff_schedule_groups (store_id, department_id)
    where deleted_at is null;

create table staff_schedule_members (
    id            uuid primary key,
    store_id      uuid    not null references stores (id),
    department_id uuid    not null references staff_departments (id),
    employee_id   uuid    not null references staff_employees (id),
    group_id      uuid references staff_schedule_groups (id),
    position      integer not null,
    constraint staff_schedule_members_uq unique (store_id, department_id, employee_id)
);
