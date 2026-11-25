-- Makro ITM8 z cadencier na komputerze sklepu: zlecenie wie, które makro je wykonuje i dlaczego artykuł został
-- pominięty (np. tego ITM8 nie ma jeszcze w Mercalys).
alter table cad_priority_runs
    add column job_id  uuid references agent_macro_jobs (id),
    add column failure varchar(300);

create index cad_priority_runs_job_idx on cad_priority_runs (job_id) where job_id is not null;

-- Automatyczne poprawianie po aktualizacji danych, włączane osobno w każdym sklepie (wiersz nadpisywany).
create table cad_priority_settings (
    store_id   uuid primary key references stores (id),
    automatic  boolean     not null,
    updated_by uuid references users (id),
    updated_at timestamptz not null
);

-- Makro zlecone przez sam serwer (bez osoby) po aktualizacji danych.
alter table agent_macro_jobs alter column created_by drop not null;
