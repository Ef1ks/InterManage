-- Raporty z Importu danych wgrywane przez agenta sklepu: agent pilnuje folderów raportów (folder z ustawień importu
-- albo folder ścieżki zapisu makra), serwer prosi o nowe pliki, agent wysyła je w kawałkach, a serwer wgrywa je tym
-- samym importem co przeglądarka. Wiersz na każdą próbę wgrania pliku (kind = raport z ImportReportKind, plik
-- wskazuje nazwa i data zmiany na dysku), historia bez usuwania. status: UPLOADING (agent wysyła), IMPORTING (import
-- w toku), IMPORTED, FAILED (error; retryable = błąd serwera, plik wraca po przerwie). removed_from_disk_at: agent
-- usunął wgrany plik z folderu następnego dnia.
create table agent_report_files (
    id                   uuid primary key,
    store_id             uuid          not null references stores (id),
    agent_id             uuid references store_agents (id),
    kind                 varchar(30)   not null,
    file_name            varchar(260)  not null,
    file_modified        timestamptz   not null,
    file_size            bigint        not null,
    status               varchar(20)   not null,
    error                varchar(2000),
    retryable            boolean       not null default false,
    started_at           timestamptz   not null,
    finished_at          timestamptz,
    removed_from_disk_at timestamptz
);

create index agent_report_files_file on agent_report_files (store_id, kind, lower(file_name));
create index agent_report_files_recent on agent_report_files (store_id, started_at desc);
