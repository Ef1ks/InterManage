-- Ścieżki plików sklepu na dysku sieciowym (foldery raportów w Imporcie danych, foldery zapisu protokołów, CADBEZP,
-- plików XML…, maski nazw i ścieżki zapisu makr z Mercalys). W jednym sklepie są takie same na każdym komputerze,
-- więc ustawia się je raz dla sklepu; przeglądarka dostaje je jako podpowiedź (wybrany folder dalej jest w przeglądarce,
-- bo jej uchwytu nie da się przenieść). Klucz = rodzaj ścieżki, pusta wartość = brak; wiersz jest nadpisywany jak
-- ustawienia plików PROTO.
create table store_file_locations (
    id           uuid primary key default gen_random_uuid(),
    store_id     uuid        not null references stores (id),
    location_key varchar(80) not null,
    value        varchar(500),
    updated_by   uuid references users (id) on delete set null,
    updated_at   timestamptz not null default now(),
    constraint store_file_locations_uq unique (store_id, location_key)
);
