-- Godziny pracy na każdy dzień tygodnia osobno, a nie pon.-sob. i niedziela. Godziny stanowiska są osobne dla każdego
-- sklepu, bo stanowisko jest wspólne dla firmy, a sklepy mają różną specyfikę. Stałe godziny pracownika sklepu nie mają.
alter table staff_shifts rename column day_type to day_of_week;
alter table staff_shifts add column store_id uuid references stores (id);

drop index staff_shifts_position_uq;

-- Dotychczasowe godziny stanowiska trafiają do każdego sklepu firmy, a WEEKDAY rozkłada się na dni od poniedziałku do soboty.
insert into staff_shifts (id, position_id, day_of_week, start_time, end_time, store_id, created_by, created_at)
select gen_random_uuid(), x.position_id, day, x.start_time, x.end_time, s.id, x.created_by, x.created_at
from staff_shifts x
join staff_positions p on p.id = x.position_id
join staff_departments d on d.id = p.department_id
join stores s on coalesce(s.nip, 'store:' || s.id::text) = d.company_key
cross join lateral unnest(case when x.day_of_week = 'WEEKDAY'
    then array['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY']
    else array[x.day_of_week] end) as day
where x.deleted_at is null;

update staff_shifts set deleted_at = now()
where position_id is not null and store_id is null and deleted_at is null;

insert into staff_shifts (id, employee_id, day_of_week, start_time, end_time, created_by, created_at)
select gen_random_uuid(), x.employee_id, day, x.start_time, x.end_time, x.created_by, x.created_at
from staff_shifts x
cross join unnest(array['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY']) as day
where x.employee_id is not null and x.day_of_week = 'WEEKDAY' and x.deleted_at is null;

update staff_shifts set deleted_at = now()
where employee_id is not null and day_of_week = 'WEEKDAY' and deleted_at is null;

-- Wpisy z historią: encja czyta dni tygodnia, a WEEKDAY już nie istnieje.
update staff_shifts set day_of_week = 'MONDAY' where day_of_week = 'WEEKDAY';

alter table staff_shifts add constraint staff_shifts_store_check
    check (position_id is null or store_id is not null or deleted_at is not null);

create unique index staff_shifts_position_uq on staff_shifts (position_id, store_id, day_of_week, start_time, end_time)
    where deleted_at is null and position_id is not null;
