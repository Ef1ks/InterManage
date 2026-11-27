-- Profil w czacie: to, co osoba sama o sobie napisała (nick, opis, telefon). Wiersz nadpisywany, bo to sam opis
-- konta; widzą go osoby, z którymi konto ma wspólny sklep.
create table chat_profiles (
    user_id    uuid primary key references users (id),
    nickname   text,
    about      text,
    phone      text,
    updated_at timestamptz not null,
    check (nickname is null or length(nickname) <= 40),
    check (about is null or length(about) <= 500),
    check (phone is null or length(phone) <= 30)
);
