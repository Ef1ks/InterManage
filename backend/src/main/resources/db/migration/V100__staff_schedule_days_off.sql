-- Oznaczenie dnia wolnego w grafiku: W5 za przeciętnie pięciodniowy tydzień pracy, WN za pracę w niedzielę albo święto
-- (art. 151(11) KP), z datą tej niedzieli lub święta, żeby było widać, za co przysługuje.
create table staff_schedule_days_off (
    id           uuid primary key,
    schedule_id  uuid        not null references staff_schedules (id),
    employee_id  uuid        not null references staff_employees (id),
    day_off_date date        not null,
    kind         varchar(5)  not null,
    for_date     date,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz not null,
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz,
    constraint staff_schedule_days_off_for_check check ((kind = 'WN') = (for_date is not null))
);

create unique index staff_schedule_days_off_uq on staff_schedule_days_off (employee_id, day_off_date)
    where deleted_at is null;
-- Za jedną niedzielę albo święto należy się jeden dzień.
create unique index staff_schedule_days_off_for_uq on staff_schedule_days_off (employee_id, for_date)
    where deleted_at is null and for_date is not null;
create index staff_schedule_days_off_schedule_idx on staff_schedule_days_off (schedule_id) where deleted_at is null;

create table staff_schedule_version_days_off (
    id           uuid primary key,
    version_id   uuid       not null references staff_schedule_versions (id),
    employee_id  uuid       not null references staff_employees (id),
    day_off_date date       not null,
    kind         varchar(5) not null,
    for_date     date,
    constraint staff_schedule_version_days_off_uq unique (version_id, employee_id, day_off_date)
);

create index staff_schedule_version_days_off_employee_idx on staff_schedule_version_days_off (employee_id, day_off_date);

create trigger staff_schedule_version_days_off_read_only before update or delete on staff_schedule_version_days_off
    for each statement execute function staff_schedule_versions_read_only();
