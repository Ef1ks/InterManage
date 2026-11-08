-- Pracownicy sklepu: działy, ich stanowiska (każde z rolą, która daje uprawnienia w aplikacji) i godziny pracy.
-- Pracownik to konto z users przypisane do stanowiska w jednym sklepie. Usunięcie i koniec pracy to znaczniki.
create table staff_departments (
    id         uuid primary key,
    store_id   uuid         not null references stores (id),
    name       varchar(80)  not null,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz  not null,
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create unique index staff_departments_name_uq on staff_departments (store_id, lower(name)) where deleted_at is null;

-- Rola jest wymagana; pustą może mieć tylko usunięte stanowisko, którego rolę usunięto później (nie da się go przywrócić).
create table staff_positions (
    id            uuid primary key,
    department_id uuid         not null references staff_departments (id),
    name          varchar(80)  not null,
    role_id       uuid references roles (id) on delete set null,
    created_by    uuid references users (id) on delete set null,
    created_at    timestamptz  not null,
    deleted_by    uuid references users (id) on delete set null,
    deleted_at    timestamptz,
    constraint staff_positions_role_check check (role_id is not null or deleted_at is not null)
);

create unique index staff_positions_name_uq on staff_positions (department_id, lower(name)) where deleted_at is null;
create index staff_positions_role_idx on staff_positions (role_id) where deleted_at is null;

create table staff_employees (
    id          uuid primary key,
    store_id    uuid        not null references stores (id),
    user_id     uuid        not null references users (id),
    position_id uuid        not null references staff_positions (id),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    ended_by    uuid references users (id) on delete set null,
    ended_at    timestamptz
);

create unique index staff_employees_user_uq on staff_employees (user_id) where ended_at is null;
create index staff_employees_store_idx on staff_employees (store_id);
create index staff_employees_position_idx on staff_employees (position_id) where ended_at is null;

-- Godziny pracy: stanowiska (do wyboru) albo pracownika (jego stałe). Pracownik może mieć godziny spoza stanowiska,
-- więc to kopia wartości, a nie odwołanie. Koniec przed początkiem = zmiana przez północ.
create table staff_shifts (
    id          uuid primary key,
    position_id uuid references staff_positions (id),
    employee_id uuid references staff_employees (id),
    day_type    varchar(20) not null,
    start_time  time        not null,
    end_time    time        not null,
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz,
    constraint staff_shifts_owner_check check (num_nonnulls(position_id, employee_id) = 1),
    constraint staff_shifts_hours_check check (start_time <> end_time)
);

create unique index staff_shifts_position_uq on staff_shifts (position_id, day_type, start_time, end_time)
    where deleted_at is null and position_id is not null;
create unique index staff_shifts_employee_uq on staff_shifts (employee_id, day_type, start_time, end_time)
    where deleted_at is null and employee_id is not null;
