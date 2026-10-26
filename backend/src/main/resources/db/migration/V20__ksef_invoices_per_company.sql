-- Faktury z KSeF są wspólne dla firmy (NIP z połączenia), a nie dla sklepu: sklepy jednej firmy pobierały dotąd te
-- same faktury osobno i dzieliły jeden limit zapytań KSeF (liczony na NIP). Teraz pobieranie idzie raz na NIP, a nowy
-- sklep z połączeniem tej firmy od razu widzi jej faktury. Przy sklepie zostaje to, co jest jego: IDWew, pomijani
-- dostawcy i ręczne oznaczenie "wprowadzona".

-- Postęp i wynik pobierania faktur firmy. Z kilku sklepów tej samej firmy bierzemy najdalszy kursor: ten sklep
-- ma u siebie wszystkie faktury do tej chwili, a po scaleniu są one wspólne.
create table ksef_sync_states (
    nip          varchar(10) primary key,
    sync_cursor  timestamptz,
    synced_at    timestamptz,
    sync_ok      boolean,
    sync_message varchar(1000)
);
insert into ksef_sync_states (nip, sync_cursor, synced_at, sync_ok, sync_message)
select distinct on (nip) nip, sync_cursor, synced_at, sync_ok, sync_message
from ksef_connections
order by nip, sync_cursor desc nulls last, synced_at desc nulls last;

alter table ksef_connections
    drop column sync_cursor,
    drop column synced_at,
    drop column sync_ok,
    drop column sync_message;

-- Ręczne "wprowadzona" jest decyzją sklepu.
create table ksef_invoice_entries (
    store_id   uuid        not null references stores (id),
    invoice_id uuid        not null references ksef_invoices (id) on delete cascade,
    entered_by uuid references users (id) on delete set null,
    entered_at timestamptz not null,
    primary key (store_id, invoice_id)
);

alter table ksef_invoices add column nip varchar(10);
update ksef_invoices i set nip = c.nip from ksef_connections c where c.store_id = i.store_id;

-- Ta sama faktura pobrana przez kilka sklepów firmy zostaje raz (najwcześniej pobrana); oznaczenia sklepów i zapisany
-- XML przechodzą na nią. Kopie bez NIP-u (sklep bez połączenia) to tylko kopie z KSeF - wrócą przy pobieraniu.
create temporary table ksef_invoice_keep on commit drop as
select id, first_value(id) over (partition by nip, ksef_number order by created_at, id) as keep_id
from ksef_invoices
where nip is not null;

insert into ksef_invoice_entries (store_id, invoice_id, entered_by, entered_at)
select i.store_id, k.keep_id, i.entered_by, i.entered_at
from ksef_invoices i
join ksef_invoice_keep k on k.id = i.id
where i.entered_at is not null
on conflict do nothing;

insert into ksef_invoice_xml (invoice_id, content, downloaded_at)
select k.keep_id, x.content, x.downloaded_at
from ksef_invoice_xml x
join ksef_invoice_keep k on k.id = x.invoice_id
where k.id <> k.keep_id
on conflict do nothing;

delete from ksef_invoices i using ksef_invoice_keep k where k.id = i.id and k.id <> k.keep_id;
delete from ksef_invoices where nip is null;

-- Razem z store_id znikają unikalność (store_id, ksef_number) i indeks (store_id, issue_date).
alter table ksef_invoices
    drop column store_id,
    drop column entered_by,
    drop column entered_at,
    alter column nip set not null,
    add constraint ksef_invoices_nip_ksef_number_key unique (nip, ksef_number);
create index ksef_invoices_nip_issue on ksef_invoices (nip, issue_date);
