-- Wynagrodzenie w grafiku. Dział w sklepie może liczyć wypłaty (wpis = włączone, wyłączenie to znacznik); widzą je tylko
-- osoby, które układają grafik tego działu. Stawka za godzinę jest przy pracowniku, osobno na umowie o pracę i na
-- zleceniu, i obowiązuje od miesiąca (valid_from = pierwszy dzień), więc zmiana stawki nie przelicza wcześniejszych
-- miesięcy. Jedna aktywna stawka na pracownika, umowę i miesiąc; zmiana i usunięcie to znacznik.
create table staff_department_pay_settings (
    id            uuid primary key,
    department_id uuid        not null references staff_departments (id),
    store_id      uuid        not null references stores (id),
    created_by    uuid references users (id) on delete set null,
    created_at    timestamptz not null,
    deleted_by    uuid references users (id) on delete set null,
    deleted_at    timestamptz
);

create unique index staff_department_pay_settings_uq on staff_department_pay_settings (department_id, store_id)
    where deleted_at is null;

create table staff_hourly_rates (
    id          uuid primary key,
    employee_id uuid          not null references staff_employees (id),
    civil       boolean       not null,
    valid_from  date          not null check (extract(day from valid_from) = 1),
    rate        numeric(10, 2) not null check (rate > 0),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz   not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz
);

create unique index staff_hourly_rates_uq on staff_hourly_rates (employee_id, civil, valid_from)
    where deleted_at is null;
