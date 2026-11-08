-- Zrównoważenie dnia karty czasu pracy: brak (spóźnienie) odrobiony czasem poza grafikiem tego samego dnia.
-- Decyzja dotyczy całego dnia (od północy do północy), więc rodzaj BALANCE ma decyzję OFFSET.
alter table staff_time_decisions drop constraint staff_time_decisions_kind_check;
alter table staff_time_decisions add constraint staff_time_decisions_kind_check check (
    kind = 'OVERTIME' and decision in ('EMPLOYER_ORDER', 'EMPLOYEE_REQUEST', 'REJECTED')
    or kind = 'SHORTFALL' and decision = 'JUSTIFIED'
    or kind = 'BALANCE' and decision = 'OFFSET');
