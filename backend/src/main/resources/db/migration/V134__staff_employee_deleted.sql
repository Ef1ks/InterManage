-- Usunięcie pracownika przez właściciela: znacznik (kiedy, kto), wpis znika z list Pracowników, Kadr, Grafiku
-- i Czasu pracy, ale zostaje w bazie i da się go przywrócić. Usuwa się tylko osobę po zakończeniu pracy.
alter table staff_employees
    add column deleted_at timestamptz,
    add column deleted_by uuid references users (id) on delete set null;
