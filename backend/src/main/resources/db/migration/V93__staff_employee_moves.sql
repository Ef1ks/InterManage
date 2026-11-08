-- Pracownik należy do firmy: może przejść do innego sklepu z tym samym NIP-em. staff_employees.store_id to sklep, w
-- którym pracuje teraz, a tu zostaje historia przeniesień (grafik musi wiedzieć, gdzie ktoś pracował danego dnia).
create table staff_employee_moves (
    id               uuid primary key,
    employee_id      uuid        not null references staff_employees (id),
    from_store_id    uuid        not null references stores (id),
    to_store_id      uuid        not null references stores (id),
    from_position_id uuid        not null references staff_positions (id),
    to_position_id   uuid        not null references staff_positions (id),
    moved_by         uuid references users (id) on delete set null,
    moved_at         timestamptz not null
);

create index staff_employee_moves_employee_idx on staff_employee_moves (employee_id, moved_at);
