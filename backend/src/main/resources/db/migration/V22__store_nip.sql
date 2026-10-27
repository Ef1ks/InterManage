-- NIP firmy, do której należy sklep. Po nim KSeF podpowiada i podłącza tylko sklepy tej samej firmy (wcześniej
-- podpowiadał każdą firmę połączoną w innym sklepie użytkownika). Sklepy już połączone z KSeF dostają NIP połączenia.
alter table stores add column nip varchar(10);
update stores s set nip = c.nip from ksef_connections c where c.store_id = s.id;
