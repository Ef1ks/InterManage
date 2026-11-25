-- Funkcje agenta, które sklep włącza i wyłącza sam (wiersz nadpisywany; bez wiersza funkcja jest włączona).
create table store_agent_features (
    id         uuid primary key default gen_random_uuid(),
    store_id   uuid        not null references stores (id),
    feature    varchar(40) not null,
    enabled    boolean     not null,
    updated_by uuid references users (id),
    updated_at timestamptz not null,
    constraint store_agent_features_key unique (store_id, feature)
);
