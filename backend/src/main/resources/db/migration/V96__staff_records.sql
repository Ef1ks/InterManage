-- Kadry: profil pracownika, umowy, staż, niepełnosprawność i urlopy. Dostęp tylko z uprawnieniem STAFF_RECORDS, bo
-- niepełnosprawność to dane szczególnej kategorii (art. 22(1b) par. 3 KP). Stopień, bez rozpoznania i treści orzeczenia.
create table staff_profiles (
    employee_id        uuid primary key references staff_employees (id),
    birth_date         date,
    education_level    varchar(40),
    education_end_date date,
    first_job          boolean     not null default false,
    medical_exam_until date,
    bhp_training_until date,
    sanitary_until     date,
    updated_by         uuid references users (id) on delete set null,
    updated_at         timestamptz not null
);

create table staff_contracts (
    id               uuid primary key,
    employee_id      uuid        not null references staff_employees (id),
    kind             varchar(20) not null,
    employment_type  varchar(20),
    start_date       date        not null,
    end_date         date,
    fte_numerator    integer     not null default 1,
    fte_denominator  integer     not null default 1,
    fixed_term_exempt boolean    not null default false,
    created_by       uuid references users (id) on delete set null,
    created_at       timestamptz not null,
    deleted_by       uuid references users (id) on delete set null,
    deleted_at       timestamptz,
    constraint staff_contracts_dates_check check (end_date is null or end_date >= start_date),
    constraint staff_contracts_fte_check check (fte_numerator > 0 and fte_numerator <= fte_denominator)
);

create index staff_contracts_employee_idx on staff_contracts (employee_id) where deleted_at is null;

-- Okresy spoza tej firmy: wcześniejsze zatrudnienie oraz zlecenia, działalność i praca za granicą (art. 302(1) KP).
create table staff_work_periods (
    id          uuid primary key,
    employee_id uuid         not null references staff_employees (id),
    kind        varchar(20)  not null,
    employer    varchar(150),
    start_date  date         not null,
    end_date    date         not null,
    documented  boolean      not null default false,
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz  not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz,
    constraint staff_work_periods_dates_check check (end_date >= start_date)
);

create index staff_work_periods_employee_idx on staff_work_periods (employee_id) where deleted_at is null;

create table staff_disabilities (
    id             uuid primary key,
    employee_id    uuid        not null references staff_employees (id),
    degree         varchar(20) not null,
    start_date     date        not null,
    end_date       date,
    doctor_consent boolean     not null default false,
    created_by     uuid references users (id) on delete set null,
    created_at     timestamptz not null,
    deleted_by     uuid references users (id) on delete set null,
    deleted_at     timestamptz,
    constraint staff_disabilities_dates_check check (end_date is null or end_date >= start_date)
);

create index staff_disabilities_employee_idx on staff_disabilities (employee_id) where deleted_at is null;

create table staff_leaves (
    id          uuid primary key,
    employee_id uuid        not null references staff_employees (id),
    kind        varchar(20) not null,
    start_date  date        not null,
    end_date    date        not null,
    days        integer     not null,
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz,
    constraint staff_leaves_dates_check check (end_date >= start_date),
    constraint staff_leaves_days_check check (days > 0)
);

create index staff_leaves_employee_idx on staff_leaves (employee_id, start_date) where deleted_at is null;

-- Stan urlopu w chwili założenia kartoteki: niewykorzystane dni z lat poprzedzających podany rok.
create table staff_leave_openings (
    id          uuid primary key,
    employee_id uuid        not null references staff_employees (id),
    year        integer     not null,
    days        integer     not null,
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz,
    constraint staff_leave_openings_days_check check (days >= 0)
);

create unique index staff_leave_openings_uq on staff_leave_openings (employee_id) where deleted_at is null;
