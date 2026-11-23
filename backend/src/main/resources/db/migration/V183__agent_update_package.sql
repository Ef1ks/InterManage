-- Automatyczna aktualizacja agenta sklepu: pozycja „Do pobrania” oznaczona jako paczka agenta (ZIP z folderem agenta).
-- Jej obowiązująca wersja trafia do każdego agenta (odpowiedź /hello), a agent sam się aktualizuje.
alter table downloads add column agent_package boolean not null default false;

-- Najwyżej jedna aktywna paczka agenta.
create unique index downloads_agent_package_uq on downloads (agent_package) where agent_package and removed_at is null;
