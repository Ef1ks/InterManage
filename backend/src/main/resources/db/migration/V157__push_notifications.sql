-- Tokeny powiadomień push aplikacji mobilnej (Expo push token), kilka urządzeń na konto. Wylogowanie albo token
-- odrzucony przez usługę push to znacznik; ten sam token na nowym koncie przechodzi na nie (stary wpis ze znacznikiem).
create table user_push_tokens (
    id           uuid primary key,
    user_id      uuid         not null references users (id),
    token        varchar(300) not null,
    platform     varchar(10)  not null check (platform in ('ios', 'android')),
    created_at   timestamptz  not null,
    last_seen_at timestamptz  not null,
    deleted_at   timestamptz
);

create unique index user_push_tokens_active_uq on user_push_tokens (token) where deleted_at is null;
create index user_push_tokens_user_idx on user_push_tokens (user_id) where deleted_at is null;

-- Różnice w karcie czasu pracy, o których pracownik dostał już powiadomienie (każda raz). Brak wyjścia ma end_at
-- puste, stąd nulls not distinct.
create table staff_time_anomaly_notices (
    id          uuid primary key,
    employee_id uuid        not null references staff_employees (id),
    part        varchar(20) not null,
    start_at    timestamp   not null,
    end_at      timestamp,
    created_at  timestamptz not null,
    constraint staff_time_anomaly_notices_uq unique nulls not distinct (employee_id, part, start_at, end_at)
);
