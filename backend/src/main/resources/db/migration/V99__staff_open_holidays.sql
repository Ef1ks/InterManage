-- Święta, w które sklep wyjątkowo jest czynny. Święto dalej obniża wymiar (art. 130 § 2 KP), a praca w nim wymaga dnia
-- wolnego w okresie (art. 151(11) § 1 pkt 2), więc wpis zmienia tylko to, czy grafik traktuje dzień jako zamknięty.
create table staff_open_holidays (
    id         uuid primary key,
    store_id   uuid        not null references stores (id),
    day        date        not null,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz not null,
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create unique index staff_open_holidays_uq on staff_open_holidays (store_id, day) where deleted_at is null;
