-- Pliki PROTO sklepu: folder, do którego program sklepu wczytuje pliki (wspólny dla PROTO6 garmażu i przyjęć MM
-- oraz protokołów PROTO1-6 z wprowadzania danych; przeglądarka dostaje go jako podpowiedź przy wyborze folderu),
-- i protokoły ukryte w oknie "Wygeneruj protokół", bo nie każdy sklep z nich korzysta.
create table store_proto_settings (
    id               uuid primary key default gen_random_uuid(),
    store_id         uuid        not null references stores (id),
    folder_path      text,
    hidden_protocols text[]      not null default '{}',
    updated_by       uuid references users (id) on delete set null,
    updated_at       timestamptz not null default now(),
    constraint store_proto_settings_store_uq unique (store_id)
);
