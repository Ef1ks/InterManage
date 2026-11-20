-- Długość PIN-u do przełączania kont: strona logowania przełącza konto sama po wpisaniu ostatniej cyfry.
-- PIN-y sprzed tej migracji uczą się długości przy pierwszym udanym przejściu.
alter table user_switch_settings
    add column pin_length smallint check (pin_length between 4 and 8);
