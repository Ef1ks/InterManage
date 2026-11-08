-- Grafik: osobny dla każdego działu w każdym sklepie, układany na miesiąc. Wpis należy do grafiku sklepu, w którym
-- pracownik tego dnia pracuje, więc pojedyncze dni w innym sklepie firmy to zwykłe wpisy w tamtym grafiku, a stałe
-- przeniesienie (staff_employee_moves) niczego w nich nie zmienia.
create table staff_schedules (
    id            uuid primary key,
    store_id      uuid        not null references stores (id),
    department_id uuid        not null references staff_departments (id),
    month         date        not null,
    created_by    uuid references users (id) on delete set null,
    created_at    timestamptz not null,
    constraint staff_schedules_month_check check (extract(day from month) = 1),
    constraint staff_schedules_uq unique (store_id, department_id, month)
);

create table staff_schedule_entries (
    id          uuid primary key,
    schedule_id uuid        not null references staff_schedules (id),
    employee_id uuid        not null references staff_employees (id),
    work_date   date        not null,
    start_time  time        not null,
    end_time    time        not null,
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz,
    constraint staff_schedule_entries_hours_check check (start_time <> end_time)
);

-- Pracownik ma jedną zmianę dziennie w całej firmie: dzień w innym sklepie nie może nałożyć się na dzień u siebie.
create unique index staff_schedule_entries_uq on staff_schedule_entries (employee_id, work_date)
    where deleted_at is null;
create index staff_schedule_entries_schedule_idx on staff_schedule_entries (schedule_id) where deleted_at is null;

-- Każda publikacja to zrzut grafiku. Wersja 1 to rozkład przekazany pracownikom pierwszy raz (art. 129 § 3 KP), a późniejsze
-- zmiany są kolejnymi wersjami, więc zrzutów nie wolno zmieniać.
create table staff_schedule_versions (
    id           uuid primary key,
    schedule_id  uuid        not null references staff_schedules (id),
    number       integer     not null,
    published_by uuid references users (id),
    published_at timestamptz not null,
    constraint staff_schedule_versions_uq unique (schedule_id, number)
);

create table staff_schedule_version_entries (
    id          uuid primary key,
    version_id  uuid not null references staff_schedule_versions (id),
    employee_id uuid not null references staff_employees (id),
    work_date   date not null,
    start_time  time not null,
    end_time    time not null,
    constraint staff_schedule_version_entries_uq unique (version_id, employee_id, work_date)
);

create index staff_schedule_version_entries_employee_idx on staff_schedule_version_entries (employee_id, work_date);

create function staff_schedule_versions_read_only() returns trigger language plpgsql as $$
begin
    raise exception 'Opublikowanej wersji grafiku nie można zmieniać';
end;
$$;

create trigger staff_schedule_versions_read_only before update or delete on staff_schedule_versions
    for each statement execute function staff_schedule_versions_read_only();
create trigger staff_schedule_version_entries_read_only before update or delete on staff_schedule_version_entries
    for each statement execute function staff_schedule_versions_read_only();

-- Kto układa grafik działu. Dział jest wspólny dla firmy, więc kierownik układa go w sklepach, do których ma dostęp.
create table staff_department_managers (
    id            uuid primary key,
    department_id uuid        not null references staff_departments (id),
    user_id       uuid        not null references users (id),
    created_by    uuid references users (id) on delete set null,
    created_at    timestamptz not null,
    deleted_by    uuid references users (id) on delete set null,
    deleted_at    timestamptz
);

create unique index staff_department_managers_uq on staff_department_managers (department_id, user_id)
    where deleted_at is null;
create index staff_department_managers_user_idx on staff_department_managers (user_id) where deleted_at is null;

-- Najmniejsza obsada stanowiska na dzień, osobno w każdym sklepie, jak godziny stanowisk. Tylko ostrzega.
create table staff_position_minimums (
    id          uuid primary key,
    position_id uuid        not null references staff_positions (id),
    store_id    uuid        not null references stores (id),
    min_count   integer     not null,
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz,
    constraint staff_position_minimums_count_check check (min_count > 0)
);

create unique index staff_position_minimums_uq on staff_position_minimums (position_id, store_id)
    where deleted_at is null;
