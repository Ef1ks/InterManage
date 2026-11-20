-- Zlecanie zadań dla wybranych kont: właściciel zleca zawsze, a innym kontom włącza je w Użytkownikach.
-- Takie konto zleca i prowadzi tylko zadania, które samo zleciło.
alter table users add column can_assign_tasks boolean not null default false;
