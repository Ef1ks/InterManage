-- Plik nowych i zmienionych artykułów (CADBEZP) zapisany przez agenta w folderze sklepu od razu wczytuje do programu
-- sklepu makro na komputerze sklepu. macro_requested_at: plik czeka na makro (ustawia je zapis agenta), macro_job_id:
-- makro, które go wczytuje albo wczytało (ponowienie wskazuje nowe).
alter table store_file_queue add column macro_requested_at timestamptz;
alter table store_file_queue add column macro_job_id uuid references agent_macro_jobs (id);

create index store_file_queue_macro_waiting on store_file_queue (store_id, macro_requested_at)
    where macro_requested_at is not null and macro_job_id is null;
create index store_file_queue_macro_job on store_file_queue (macro_job_id) where macro_job_id is not null;
