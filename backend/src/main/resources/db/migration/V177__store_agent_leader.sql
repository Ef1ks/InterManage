-- Kilku agentów w jednym sklepie (agent na każdym komputerze): pliki z kolejki zapisuje każdy (przydział jest
-- bezpieczny), ale pilnowanie folderów (ceny z kasy, CTRLPRIX, ATHOS, raporty) i aktualizację danych z Mercalys robi
-- jeden, główny agent. Główny ma dzierżawę na serwerze, przedłużaną każdym jego żądaniem; po wygaśnięciu (agent
-- niedostępny) przejmuje ją pierwszy zgłaszający się agent sklepu.
--
-- preferred: agent preferowany (np. komputer w biurze, nie kasa) odbiera dzierżawę agentowi niepreferowanemu, gdy
-- tylko się zgłosi. Ustawienie klucza, nadpisywane.
alter table store_agents
    add column preferred boolean not null default false;

-- Dzierżawa głównego agenta sklepu: jeden wiersz na sklep, nadpisywany (to stan, nie historia; zmiany widać w logach).
-- since: od kiedy ten agent jest głównym. update_agent_id / update_started_at: aktualizacja danych z Mercalys w toku
-- (blokada, żeby dwa komputery nie uruchomiły makra naraz; zdejmuje ją koniec aktualizacji, a po godzinie wygasa).
create table store_agent_leaders (
    store_id          uuid primary key references stores (id),
    agent_id          uuid        not null references store_agents (id),
    since             timestamptz not null,
    expires_at        timestamptz not null,
    update_agent_id   uuid references store_agents (id),
    update_started_at timestamptz
);
