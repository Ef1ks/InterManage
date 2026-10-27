-- Korekta (błędu sklepu PK albo od dostawcy WZ) przypięta do przyjęcia PZ, którego dotyczy. Raport nie mówi, którą
-- dostawę poprawia korekta, więc przypina się ją ręcznie. Wartości przyjęcia i wydruk bonu dostawy liczą się wtedy
-- z linii przyjęcia razem z liniami korekt (po ITM8). Korekta poprawia jedno przyjęcie, przyjęcie może mieć kilka
-- korekt. Odpięcie to znacznik (unlinked_at/by), wpis zostaje.
create table delivery_corrections (
    id            uuid primary key,
    delivery_id   uuid        not null references documents (id),
    correction_id uuid        not null references documents (id),
    linked_by     uuid references users (id),
    linked_at     timestamptz not null,
    unlinked_by   uuid references users (id),
    unlinked_at   timestamptz
);

create unique index delivery_corrections_active_correction_uq
    on delivery_corrections (correction_id) where unlinked_at is null;
create index delivery_corrections_delivery_idx on delivery_corrections (delivery_id) where unlinked_at is null;
