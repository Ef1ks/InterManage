-- Zmiany umów. Aneks zmienia etat od wskazanego dnia, więc wcześniejszy etat zostaje dla swojego okresu i urlop oraz
-- godziny liczą się dla każdego dnia z etatu, który wtedy obowiązywał. Aneks to ta sama umowa, a nie kolejna.
create table staff_contract_changes (
    id            uuid primary key,
    employee_id   uuid        not null references staff_employees (id),
    contract_id   uuid        not null references staff_contracts (id),
    valid_from    date        not null,
    fte_numerator   integer   not null,
    fte_denominator integer   not null,
    created_by    uuid references users (id) on delete set null,
    created_at    timestamptz not null,
    deleted_by    uuid references users (id) on delete set null,
    deleted_at    timestamptz,
    constraint staff_contract_changes_fte_check check (fte_numerator > 0 and fte_numerator <= fte_denominator)
);

create unique index staff_contract_changes_uq on staff_contract_changes (contract_id, valid_from)
    where deleted_at is null;
create index staff_contract_changes_employee_idx on staff_contract_changes (employee_id) where deleted_at is null;

-- Poprzednie wersje umowy: poprawka pomyłki nadpisuje wpis, a tu zostaje to, co w nim było.
create table staff_contract_revisions (
    id                uuid primary key,
    contract_id       uuid        not null references staff_contracts (id),
    kind              varchar(20) not null,
    employment_type   varchar(20),
    start_date        date        not null,
    end_date          date,
    fte_numerator     integer     not null,
    fte_denominator   integer     not null,
    fixed_term_exempt boolean     not null,
    changed_by        uuid references users (id) on delete set null,
    changed_at        timestamptz not null
);

create index staff_contract_revisions_contract_idx on staff_contract_revisions (contract_id);
