-- Ruchomy czas pracy w sklepie (art. 140¹ KP, z regulaminu pracy albo obwieszczenia): ponowna praca w tej samej dobie
-- pracowniczej nie jest nadgodzinami, więc grafik nie ostrzega o wcześniejszym starcie. Aktywny wiersz = włączone,
-- wyłączenie to znacznik.
create table staff_flexible_time_settings (
    id         uuid primary key,
    store_id   uuid        not null references stores (id),
    created_by uuid references users (id) on delete set null,
    created_at timestamptz not null,
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create unique index staff_flexible_time_settings_uq on staff_flexible_time_settings (store_id) where deleted_at is null;
