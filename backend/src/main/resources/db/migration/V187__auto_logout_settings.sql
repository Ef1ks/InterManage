-- Automatyczne wylogowywanie ustawia każdy na swoim koncie (Moje konto). auto_logout = false: ani po bezczynności,
-- ani po godzinie końca pracy. idle_lock_minutes: po ilu minutach bez ruchu, puste = domyślne aplikacji
-- (app.auth.idle-lock-after). workday_lock: także po godzinie końca pracy (app.auth.workday-lock-at).
alter table user_switch_settings
    add column auto_logout       boolean not null default true,
    add column idle_lock_minutes integer
        constraint user_switch_settings_idle_ck check (idle_lock_minutes between 5 and 480),
    add column workday_lock      boolean not null default true;
