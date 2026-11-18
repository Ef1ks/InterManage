-- Dostawca do kontroli strat może być podpięty pod dostawcę z listy dostawców sklepu: jego gratisy za pokrycie strat
-- pokazują się w raporcie protokołów przy tym dostawcy do kontroli.
alter table loss_control_groups add column supplier_id uuid references suppliers (id);

create index loss_control_groups_supplier_idx on loss_control_groups (supplier_id) where supplier_id is not null;
