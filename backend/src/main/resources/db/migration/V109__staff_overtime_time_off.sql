-- Czas wolny w zamian za nadgodziny (art. 151² KP), ułożony w grafiku: na wniosek pracownika godzina za godzinę
-- (§ 1), z inicjatywy pracodawcy półtorej godziny za godzinę, najpóźniej do końca okresu rozliczeniowego (§ 2).
-- Wpis to minuty czasu wolnego danego dnia; dzień może mieć też (krótszą) zmianę. Czas wolny liczy się do wymiaru jak
-- praca, bo nie obniża wynagrodzenia za pełny wymiar (§ 3). Jeden aktywny wpis na pracownika i dzień, zmiana i
-- usunięcie to znacznik. Grafik sklepu wskazuje, kto go ułożył; poza wersjami grafiku.
create table staff_overtime_time_off (
    id          uuid primary key,
    employee_id uuid        not null references staff_employees (id),
    store_id    uuid        not null references stores (id),
    work_date   date        not null,
    minutes     integer     not null check (minutes > 0 and minutes <= 1440),
    initiative  varchar(20) not null check (initiative in ('EMPLOYEE', 'EMPLOYER')),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz
);

create unique index staff_overtime_time_off_uq on staff_overtime_time_off (employee_id, work_date)
    where deleted_at is null;
