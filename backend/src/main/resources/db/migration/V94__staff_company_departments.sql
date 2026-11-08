-- Działy i stanowiska są wspólne dla firmy, bo kadry prowadzą kilka sklepów naraz. Firma to NIP sklepu, a sklep bez
-- NIP-u jest firmą sam dla siebie (tak samo jak w supplier_profiles). Pracownik dalej ma sklep, w którym pracuje.
alter table staff_departments add column company_key varchar(60);

update staff_departments d
set company_key = coalesce(s.nip, 'store:' || s.id::text)
from stores s
where s.id = d.store_id;

alter table staff_departments alter column company_key set not null;

drop index staff_departments_name_uq;
alter table staff_departments drop column store_id;

create unique index staff_departments_name_uq on staff_departments (company_key, lower(name)) where deleted_at is null;
create index staff_departments_company_idx on staff_departments (company_key);
