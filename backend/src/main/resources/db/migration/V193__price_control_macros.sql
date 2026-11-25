-- Zmiana cen (CTRLPRIX), która pojawiła się w folderze sklepu, sama zleca makro na preferowanym komputerze sklepu.
-- macro_requested_at: czeka na makro, macro_job_id: makro, które ją wprowadza albo wprowadziło, macro_retry_at: po
-- „Anuluj” przy komputerze (albo gdy ktoś na nim pracował) kolejna próba nie wcześniej niż wtedy.
alter table data_entry_price_control_files
    add column macro_requested_at timestamptz,
    add column macro_job_id       uuid references agent_macro_jobs (id),
    add column macro_retry_at     timestamptz;

create index data_entry_price_control_files_macro_waiting
    on data_entry_price_control_files (store_id, macro_requested_at)
    where macro_requested_at is not null and macro_job_id is null and loaded_at is null and deleted_at is null;
create index data_entry_price_control_files_macro_job
    on data_entry_price_control_files (macro_job_id) where macro_job_id is not null;
