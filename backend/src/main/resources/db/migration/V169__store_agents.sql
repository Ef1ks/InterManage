-- Agent sklepu: program na komputerze w sklepie (agent/), który zapisuje pliki z kolejki sklepu w folderach na dysku
-- sieciowym i pilnuje folderów (TRACEART, zmiany cen CTRLPRIX, dziennik ATHOS). Przedstawia się kluczem pokazywanym
-- raz; w bazie tylko SHA-256 klucza (jak czytniki kart, staff_time_readers). last_seen_at odświeża każde żądanie
-- agenta (także otwarte czekanie na pliki), więc agent jest online, gdy kontakt był w ostatniej minucie.
-- Usunięcie to znacznik (deleted_at, deleted_by).
create table store_agents (
    id           uuid primary key,
    store_id     uuid         not null references stores (id),
    name         varchar(100) not null,
    key_hash     varchar(64)  not null unique,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz  not null,
    last_seen_at timestamptz,
    last_version varchar(50),
    machine_name varchar(100),
    machine_user varchar(100),
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz
);

create index store_agents_store on store_agents (store_id) where deleted_at is null;

-- Czy sklep pracuje z folderami przez agenta, czy jak dotąd przez przeglądarkę (wybór w Ustawieniach sklepu).
-- Jeden wiersz na sklep, nadpisywany, bo to samo ustawienie; brak wiersza = przeglądarka.
create table store_agent_settings (
    store_id   uuid primary key references stores (id),
    enabled    boolean     not null,
    updated_by uuid references users (id) on delete set null,
    updated_at timestamptz not null
);

-- Przydział pliku z kolejki przed zapisem: agent albo przeglądarka (zapas) najpierw przydziela plik sobie jednym
-- zapytaniem warunkowym, więc plik nie zapisze się dwa razy. Przydział jest ważny 2 minuty; saved i failed przyjmujemy
-- tylko od posiadacza. failed_at / last_error: nieudany zapis (agent nie bierze go już sam, zapisuje go przeglądarka).
alter table store_file_queue
    add column claimed_at          timestamptz,
    add column claimed_by_agent_id uuid references store_agents (id),
    add column claimed_by_user_id  uuid references users (id) on delete set null,
    add column claim_expires_at    timestamptz,
    add column saved_by_agent_id   uuid references store_agents (id),
    add column failed_at           timestamptz,
    add column last_error          varchar(500);
