-- Kolejka makr sklepu: zlecenie z przeglądarki, gdy w sklepie trwa już makro albo żaden komputer nie jest wolny,
-- czeka (status QUEUED) i rusza samo, po kolei według chwili zlecenia. Nie wygasa: zdejmuje je tylko „Anuluj”.
-- wait_reason: dlaczego jeszcze czeka, retry_at: najwcześniej wtedy (po „Anuluj” na wszystkich komputerach).
alter table agent_macro_jobs
    add column wait_reason text,
    add column retry_at    timestamptz;

create index agent_macro_jobs_queued on agent_macro_jobs (store_id, created_at) where status = 'QUEUED';
