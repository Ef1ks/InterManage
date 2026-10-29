-- Ilości, które naprawdę przyjechały w ruchu międzymagazynowym: sklep docelowy wpisuje je przy wprowadzaniu
-- dokumentu u siebie, a z nich powstaje plik PROTO6 i Excel. Linia bez wpisu = ilość z dokumentu.
alter table document_transfer_targets
    add column received_by uuid references users (id) on delete set null,
    add column received_at timestamptz;

create table document_transfer_received_lines (
    id               uuid primary key default gen_random_uuid(),
    target_id        uuid           not null references document_transfer_targets (id),
    document_line_id uuid           not null references document_lines (id),
    quantity         numeric(14, 3) not null check (quantity >= 0),
    constraint document_transfer_received_lines_uq unique (target_id, document_line_id)
);
