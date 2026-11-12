-- Przełączanie kont w przeglądarce: kilka kont zalogowanych naraz (aktywne w ciasteczku AUTH_TOKEN, pozostałe
-- w AUTH_ACCOUNTS), przejście na konto wymaga jego PIN-u. Ustawienia należą do konta, na które się przełącza:
-- pin_hash (zakodowany jak hasło, pusty = PIN nieustawiony, wtedy przejście wymaga hasła), pin_required = false
-- pozwala przełączyć się bez PIN-u, failed_attempts liczy błędne PIN-y (po 5 przejście wymaga hasła, które je zeruje).
-- Jeden wiersz na konto, zmiana go nadpisuje (jak hasło w users).
create table user_switch_settings (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid         not null references users (id),
    pin_hash        varchar(255),
    pin_required    boolean      not null default true,
    failed_attempts integer      not null default 0
        constraint user_switch_settings_attempts_ck check (failed_attempts >= 0),
    updated_at      timestamptz  not null default now(),
    constraint user_switch_settings_user_uq unique (user_id)
);
