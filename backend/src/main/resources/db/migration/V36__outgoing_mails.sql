-- Kolejka maili: wiersz powstaje w transakcji, która wysyła maila (np. zaproszenie), a wysyłka idzie po zatwierdzeniu
-- i jest ponawiana, gdy serwer pocztowy nie odpowiada. Wiersze zostają jako historia wysłanych wiadomości.
create table outgoing_mails
(
    id              uuid primary key,
    kind            varchar(40)  not null,
    recipient_email varchar(320) not null,
    recipient_name  varchar(200),
    subject         varchar(300) not null,
    -- Treść maili z jednorazowym linkiem jest po wysłaniu (albo porzuceniu) zastępowana opisem.
    text_body       text         not null,
    html_body       text         not null,
    created_at      timestamptz  not null,
    next_attempt_at timestamptz  not null,
    attempts        int          not null default 0,
    last_error      varchar(2000),
    sent_at         timestamptz,
    -- Porzucony po ostatniej nieudanej próbie.
    failed_at       timestamptz
);

create index outgoing_mails_due_idx on outgoing_mails (next_attempt_at) where sent_at is null and failed_at is null;
