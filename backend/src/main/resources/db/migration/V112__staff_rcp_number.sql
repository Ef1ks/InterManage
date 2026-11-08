-- Numer karty RCP pracownika: nadaje go aplikacja, a kadry wpisują go w Symfonii (R2Płatnik, Aktualne zatrudnienie),
-- bo po nim import ewidencji czasu pracy rozpoznaje pracownika. Jeden numer na pracownika na zawsze (zostaje po
-- przeniesieniu i po końcu pracy, nie wraca do puli). Obecni pracownicy dostają numery od 1001 w kolejności założenia.
create sequence staff_rcp_number_seq start with 1001;

alter table staff_employees add column rcp_number integer;

update staff_employees e
set rcp_number = numbered.number
from (select id, 1000 + row_number() over (order by created_at, id) as number from staff_employees) numbered
where numbered.id = e.id;

select setval('staff_rcp_number_seq', greatest(1001, coalesce((select max(rcp_number) + 1 from staff_employees), 1001)),
              false);

alter table staff_employees alter column rcp_number set default nextval('staff_rcp_number_seq');
alter table staff_employees alter column rcp_number set not null;
alter sequence staff_rcp_number_seq owned by staff_employees.rcp_number;

create unique index staff_employees_rcp_number_idx on staff_employees (rcp_number);
