-- Przerwy działu w sklepie (godziny od-do, płatna albo nie). Grafik ich nie pokazuje; posłużą rejestracji czasu pracy.
create table staff_department_breaks (
    id            uuid primary key,
    department_id uuid        not null references staff_departments (id),
    store_id      uuid        not null references stores (id),
    start_time    time        not null,
    end_time      time        not null,
    paid          boolean     not null,
    created_by    uuid references users (id) on delete set null,
    created_at    timestamptz not null,
    deleted_by    uuid references users (id) on delete set null,
    deleted_at    timestamptz,
    constraint staff_department_breaks_time_check check (end_time > start_time)
);

create index staff_department_breaks_department_idx on staff_department_breaks (department_id, store_id)
    where deleted_at is null;
