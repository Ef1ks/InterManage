-- Odpowiedź na wiadomość: wiadomość wskazuje tę, na którą odpowiada (z tej samej rozmowy, sprawdza serwer).
alter table chat_messages add column reply_to_id uuid references chat_messages (id);

-- Ustawienia czatu konta (wiersz nadpisywany, sam wybór). read_receipts = false: inni nie widzą, że konto przeczytało
-- wiadomość, a konto nie widzi tego u innych (jak w WhatsAppie, w obie strony). sound: kiedy przeglądarka gra dźwięk.
create table chat_user_settings (
    user_id       uuid primary key references users (id),
    read_receipts boolean     not null default true,
    sound         text        not null default 'MENTIONS' check (sound in ('NONE', 'MENTIONS', 'ALL')),
    updated_at    timestamptz not null
);

-- Wyciszenia konta: osoba (USER, target = konto) albo rozmowa (CONVERSATION, target = rozmowa). Wyciszone nie dają
-- powiadomień ani dźwięku; wyciszona rozmowa nie liczy się też do licznika. Odciszenie to znacznik.
create table chat_mutes (
    id         uuid primary key,
    user_id    uuid        not null references users (id),
    kind       text        not null check (kind in ('USER', 'CONVERSATION')),
    target_id  uuid        not null,
    created_at timestamptz not null,
    removed_at timestamptz
);

create unique index chat_mutes_active_uq on chat_mutes (user_id, kind, target_id) where removed_at is null;
