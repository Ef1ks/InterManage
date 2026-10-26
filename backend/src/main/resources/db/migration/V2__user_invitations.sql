-- Zaproszenia: konto zakłada właściciel (bez hasła), pracownik aktywuje je linkiem z maila i ustawia własne hasło.
alter table users alter column password_hash drop not null;
alter table users add column email varchar(255);
alter table users add column password_change_required boolean not null default false;

create unique index users_email_uq on users (lower(email));

create table user_activation_tokens (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid         not null references users (id) on delete cascade,
    -- SHA-256 tokenu (hex); sam token jest tylko w linku.
    token_hash varchar(64)  not null,
    expires_at timestamptz  not null,
    used_at    timestamptz,
    created_at timestamptz  not null default now()
);

create unique index user_activation_tokens_hash_uq on user_activation_tokens (token_hash);
create index user_activation_tokens_user_id_idx on user_activation_tokens (user_id);
