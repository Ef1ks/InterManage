-- Nowe decyzje o odcinkach karty: odpracowanie wyjścia prywatnego (czas poza grafikiem liczy się do pracy, ale nie
-- jest nadgodzinami, art. 151²¹ § 2 KP), wyjście prywatne do odpracowania (brak bez wynagrodzenia, do odrobienia),
-- usprawiedliwienie płatne w 50% (siła wyższa, art. 148¹ KP) i bez wynagrodzenia (np. ważna przyczyna, wezwanie).
alter table staff_time_decisions drop constraint staff_time_decisions_kind_check;
alter table staff_time_decisions add constraint staff_time_decisions_kind_check check (
    kind = 'OVERTIME' and decision in ('EMPLOYER_ORDER', 'EMPLOYEE_REQUEST', 'REJECTED', 'WORKED_OFF')
    or kind = 'SHORTFALL' and decision in ('JUSTIFIED', 'JUSTIFIED_HALF', 'JUSTIFIED_UNPAID', 'PRIVATE_EXIT')
    or kind = 'BALANCE' and decision = 'OFFSET');

-- Wnioski pracowników o odcinki karty (nadgodziny z własnej inicjatywy, wyjście prywatne, spóźnienie z przyczyną…)
-- i zgłoszenia L4. Kierownik działu akceptuje albo odrzuca; gdy sklep wybrał kadry, zaakceptowany wniosek czeka na
-- kadry. Ostateczna akceptacja zapisuje decyzję w karcie (decision_id) albo zwolnienie w Kadrach (leave_id).
create table staff_time_requests (
    id            uuid primary key,
    employee_id   uuid          not null references staff_employees (id),
    store_id      uuid          not null references stores (id),
    kind          varchar(30)   not null,
    work_date     date          not null,
    date_to       date,
    start_at      timestamp,
    end_at        timestamp,
    reason        varchar(1000),
    status        varchar(20)   not null,
    created_by    uuid references users (id) on delete set null,
    created_at    timestamptz   not null,
    manager_by    uuid references users (id) on delete set null,
    manager_at    timestamptz,
    manager_note  varchar(500),
    hr_by         uuid references users (id) on delete set null,
    hr_at         timestamptz,
    hr_note       varchar(500),
    decision_id   uuid references staff_time_decisions (id),
    leave_id      uuid references staff_leaves (id),
    constraint staff_time_requests_status_check
        check (status in ('PENDING', 'MANAGER_APPROVED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    constraint staff_time_requests_range_check check (
        start_at is null and end_at is null and date_to is not null and date_to >= work_date
        or start_at is not null and end_at > start_at and date_to is null)
);

create index staff_time_requests_store_idx on staff_time_requests (store_id, status, work_date);
create index staff_time_requests_employee_idx on staff_time_requests (employee_id, work_date);

-- Historia wniosku: każdy krok (złożenie, akceptacja, odrzucenie, cofnięcie) z poprzednim i nowym stanem.
create table staff_time_request_events (
    id          uuid primary key,
    request_id  uuid         not null references staff_time_requests (id),
    action      varchar(20)  not null,
    from_status varchar(20),
    to_status   varchar(20)  not null,
    note        varchar(500),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz  not null
);

create index staff_time_request_events_request_idx on staff_time_request_events (request_id, created_at);
