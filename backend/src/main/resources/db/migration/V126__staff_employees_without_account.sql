-- Pracownik bez konta w aplikacji: samo imię i nazwisko (grafik, czas pracy, kadry), bez loginu i e-maila.
-- Pracownik z kontem bierze imię i nazwisko z konta, więc full_name ma tylko pracownik bez konta.
alter table staff_employees alter column user_id drop not null;
alter table staff_employees add column full_name varchar(100);
alter table staff_employees add constraint staff_employees_name_check
    check ((user_id is not null) <> (full_name is not null));
