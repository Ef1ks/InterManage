-- Stanowisko na dany dzień: pracownik może w różne dni pracować na różnych stanowiskach działu. Puste = jego stałe
-- stanowisko, więc dotychczasowe grafiki się nie zmieniają. Obsada pod grafikiem liczy osoby według stanowiska dnia.
alter table staff_schedule_entries add column position_id uuid references staff_positions (id);
alter table staff_schedule_version_entries add column position_id uuid references staff_positions (id);

-- Stanowiska, na których pracownik może pracować poza stałym (umie je obsłużyć). Tylko je da się wpisać mu na dzień.
-- Usunięcie to znacznik.
create table staff_employee_positions (
    id          uuid primary key,
    employee_id uuid        not null references staff_employees (id),
    position_id uuid        not null references staff_positions (id),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz
);

create unique index staff_employee_positions_uq on staff_employee_positions (employee_id, position_id)
    where deleted_at is null;
