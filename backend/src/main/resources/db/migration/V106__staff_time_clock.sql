-- Karty czasu pracy: odbicia kart NFC z czytników, decyzje kierownika o czasie poza grafikiem i dane pracodawcy do
-- wydruków. Usunięcie to znacznik, jak wszędzie.

-- Czytnik w sklepie; w bazie tylko SHA-256 jego klucza (pokazywanego raz przy dodaniu).
create table staff_time_readers (
    id         uuid primary key,
    store_id   uuid         not null references stores (id),
    name       varchar(100) not null,
    token_hash varchar(64)  not null,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz  not null,
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create unique index staff_time_readers_token_uq on staff_time_readers (token_hash) where deleted_at is null;

-- Karta NFC pracownika: numer (UID) odczytany przez czytnik.
create table staff_nfc_cards (
    id          uuid primary key,
    employee_id uuid        not null references staff_employees (id),
    card_uid    varchar(64) not null,
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz
);

create unique index staff_nfc_cards_uid_uq on staff_nfc_cards (card_uid) where deleted_at is null;
create unique index staff_nfc_cards_employee_uq on staff_nfc_cards (employee_id) where deleted_at is null;

-- Odbicie: kolejne odbicia pracownika to na zmianę wejście i wyjście.
create table staff_time_punches (
    id          uuid primary key,
    employee_id uuid        not null references staff_employees (id),
    store_id    uuid        not null references stores (id),
    punched_at  timestamptz not null,
    source      varchar(20) not null,
    reader_id   uuid references staff_time_readers (id),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz,
    constraint staff_time_punches_source_check check (source in ('READER', 'SIMULATED'))
);

create index staff_time_punches_employee_idx on staff_time_punches (employee_id, punched_at) where deleted_at is null;

-- Decyzja kierownika o odcinku poza grafikiem (nadgodziny) albo brakującym czasie (spóźnienie, wcześniejsze wyjście).
create table staff_time_decisions (
    id          uuid primary key,
    employee_id uuid        not null references staff_employees (id),
    work_date   date        not null,
    kind        varchar(20) not null,
    start_at    timestamp   not null,
    end_at      timestamp   not null,
    decision    varchar(30) not null,
    note        varchar(500),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null,
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz,
    constraint staff_time_decisions_range_check check (end_at > start_at),
    constraint staff_time_decisions_kind_check check (
        kind = 'OVERTIME' and decision in ('EMPLOYER_ORDER', 'EMPLOYEE_REQUEST', 'REJECTED')
        or kind = 'SHORTFALL' and decision = 'JUSTIFIED')
);

create unique index staff_time_decisions_uq on staff_time_decisions (employee_id, kind, start_at, end_at)
    where deleted_at is null;
create index staff_time_decisions_day_idx on staff_time_decisions (employee_id, work_date) where deleted_at is null;

-- Tolerancja działu w sklepie: odcinki poza grafikiem i braki do tylu minut nie czekają na decyzję.
create table staff_department_time_settings (
    id                uuid primary key,
    department_id     uuid        not null references staff_departments (id),
    store_id          uuid        not null references stores (id),
    tolerance_minutes integer     not null,
    created_by        uuid references users (id) on delete set null,
    created_at        timestamptz not null,
    deleted_by        uuid references users (id) on delete set null,
    deleted_at        timestamptz,
    constraint staff_department_time_settings_tolerance_check check (tolerance_minutes between 0 and 60)
);

create unique index staff_department_time_settings_uq on staff_department_time_settings (department_id, store_id)
    where deleted_at is null;

-- Dane pracodawcy do wydruków (polecenie i wniosek o pracę w godzinach nadliczbowych), wspólne dla firmy (NIP).
create table staff_employer_details (
    id             uuid primary key,
    company_key    varchar(64)  not null,
    name           varchar(200) not null,
    address        varchar(300),
    nip            varchar(10),
    regon          varchar(14),
    representative varchar(200),
    created_by     uuid references users (id) on delete set null,
    created_at     timestamptz  not null,
    deleted_by     uuid references users (id) on delete set null,
    deleted_at     timestamptz
);

create unique index staff_employer_details_uq on staff_employer_details (company_key) where deleted_at is null;
