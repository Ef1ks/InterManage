-- Dostawcy zakładani w programie sklepu (Mercalys) makrem z wprowadzania danych, gdy sprzedawcy z faktury nie ma na
-- liście dostawców. Wpis powstaje przy skopiowaniu makra i rezerwuje kod: do wgrania nowej listy dostawców kolejne
-- makro nie dostanie tego samego numeru, a ponowne zakładanie tego NIP-u jest ostrzegane. Makro i tak sprawdza
-- w Mercalys, czy kod jest wolny. deleted_at: makro cofnięte (nie uruchomione), kod wraca do puli.
create table mercalys_supplier_creations (
    id         uuid primary key default gen_random_uuid(),
    store_id   uuid        not null references stores (id),
    nip        varchar(10) not null,
    code       varchar(8)  not null,
    name       text        not null,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz not null default now(),
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create index mercalys_supplier_creations_store_idx on mercalys_supplier_creations (store_id);
