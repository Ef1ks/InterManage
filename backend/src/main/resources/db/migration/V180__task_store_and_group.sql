-- Zadanie dla całego sklepu (bez wykonawcy: wykonuje ktokolwiek z dostępem do sklepu) i zlecenie kilku osobom naraz
-- (każda dostaje swoją kopię, kopie łączy group_id, żeby zlecający widział postęp i dało się zamknąć wszystkie naraz).
alter table store_tasks alter column assignee_id drop not null;
alter table store_tasks add column for_store boolean not null default false;
alter table store_tasks add column group_id uuid;
alter table store_tasks add constraint store_tasks_target_ck check (for_store or assignee_id is not null);

create index store_tasks_for_store_idx on store_tasks (store_id, status) where for_store and deleted_at is null;
create index store_tasks_group_idx on store_tasks (group_id) where group_id is not null;
