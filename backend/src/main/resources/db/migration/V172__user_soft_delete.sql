-- Usunięcie konta przez właściciela: znacznik (kiedy, kto), konto jest wtedy zablokowane i znika z listy
-- użytkowników, a toast „Przywróć” oddaje je w stanie sprzed usunięcia (enabled_before_delete). Login zostaje zajęty,
-- a adres e-mail się zwalnia: unikalny jest już tylko wśród nieusuniętych kont.
alter table users
    add column deleted_at            timestamptz,
    add column deleted_by            uuid references users (id) on delete set null,
    add column enabled_before_delete boolean;

drop index users_email_uq;
create unique index users_email_uq on users (lower(email)) where deleted_at is null;
