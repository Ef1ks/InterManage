-- Kopia zapasowa bazy na Dysk Google (Ustawienia → Kopia zapasowa, tylko właściciel). Jeden wiersz ustawień.
-- Sekrety (hasło szyfrowania kopii, refresh token Google) zaszyfrowane kluczem APP_SECRET_KEY (SecretCipher).
create table backup_settings (
    id                     smallint primary key default 1 check (id = 1),
    enabled                boolean      not null default false,
    run_hour               smallint     not null default 3 check (run_hour between 0 and 23),
    keep_count             smallint     not null default 30 check (keep_count between 1 and 365),
    password_encrypted     text,
    password_set_at        timestamptz,
    password_set_by        uuid references users (id) on delete set null,
    google_email           varchar(320),
    google_refresh_token   text,
    google_folder_id       varchar(200),
    google_connected_at    timestamptz,
    google_connected_by    uuid references users (id) on delete set null,
    -- Jednorazowy parametr state logowania OAuth (ochrona przed podrzuceniem cudzego konta), ważny kilka minut.
    oauth_state            varchar(100),
    oauth_state_expires_at timestamptz,
    updated_at             timestamptz  not null default now(),
    updated_by             uuid references users (id) on delete set null
);

-- Każda próba kopii: automatyczna (started_by puste) albo "Zrób kopię teraz". Wiersze zostają jako historia.
create table backup_runs (
    id            uuid primary key default gen_random_uuid(),
    manual        boolean      not null,
    status        varchar(20)  not null, -- BackupStatus
    started_at    timestamptz  not null default now(),
    started_by    uuid references users (id) on delete set null,
    finished_at   timestamptz,
    file_name     varchar(200),
    size_bytes    bigint,
    google_email  varchar(320),
    drive_file_id varchar(200),
    error         text,
    -- Kopia ponad limit przeniesiona do kosza Dysku (Google opróżnia kosz po 30 dniach).
    trashed_at    timestamptz
);

create index backup_runs_started_idx on backup_runs (started_at desc);
