-- Zasady odbić w sklepie: zaokrąglanie (wejście w górę, wyjście w dół do wielokrotności minut, 0 = bez; surowy czas
-- odbicia zostaje w staff_time_punches), blokada ponownego odbicia tą samą kartą (sekundy, co najmniej 10) i kto
-- ostatecznie zatwierdza wnioski pracowników (kierownik albo kadry). Aktywny jeden wiersz na sklep, zmiana to
-- znacznik i nowy wiersz. Bez wiersza: bez zaokrąglania, 10 s, kierownik.
create table staff_time_store_rules (
    id                     uuid primary key,
    store_id               uuid        not null references stores (id),
    rounding_minutes       int         not null check (rounding_minutes in (0, 1, 2, 3, 5, 6, 10, 15, 20, 30, 60)),
    punch_cooldown_seconds int         not null check (punch_cooldown_seconds between 10 and 600),
    final_approver         varchar(20) not null check (final_approver in ('MANAGER', 'HR')),
    created_by             uuid references users (id) on delete set null,
    created_at             timestamptz not null,
    deleted_by             uuid references users (id) on delete set null,
    deleted_at             timestamptz
);

create unique index staff_time_store_rules_uq on staff_time_store_rules (store_id) where deleted_at is null;

-- Propozycja zasad dla sklepu zaprzyjaźnionego: druga strona przyjmuje albo odrzuca, nadawca może cofnąć. Oczekuje
-- najwyżej jedna od sklepu do sklepu (kolejna cofa poprzednią).
create table staff_time_rule_offers (
    id                     uuid primary key,
    from_store_id          uuid        not null references stores (id),
    to_store_id            uuid        not null references stores (id),
    rounding_minutes       int         not null,
    punch_cooldown_seconds int         not null,
    final_approver         varchar(20) not null,
    created_by             uuid references users (id) on delete set null,
    created_at             timestamptz not null,
    accepted_by            uuid references users (id) on delete set null,
    accepted_at            timestamptz,
    rejected_by            uuid references users (id) on delete set null,
    rejected_at            timestamptz,
    cancelled_by           uuid references users (id) on delete set null,
    cancelled_at           timestamptz
);

create unique index staff_time_rule_offers_pending_uq on staff_time_rule_offers (from_store_id, to_store_id)
    where accepted_at is null and rejected_at is null and cancelled_at is null;
create index staff_time_rule_offers_to_idx on staff_time_rule_offers (to_store_id);
