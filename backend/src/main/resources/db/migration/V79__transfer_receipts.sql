-- Dokument MM sklepu docelowego (zakup na plus, z jego raportu) przypięty do ruchu międzymagazynowego sklepu
-- wysyłającego: potwierdza, że sklep naprawdę wprowadził towar. Dopiero wtedy dokument przestaje czekać. Odpięcie to
-- znacznik (unlinked_at/by), wpis zostaje. Jeden dokument potwierdza jeden ruch: pilnuje tego serwis, bo wpis
-- cofniętego wskazania sklepu nie może blokować ponownego przypięcia.
create table document_transfer_receipts (
    id          uuid primary key,
    target_id   uuid        not null references document_transfer_targets (id),
    document_id uuid        not null references documents (id),
    linked_by   uuid references users (id),
    linked_at   timestamptz not null,
    unlinked_by uuid references users (id),
    unlinked_at timestamptz
);

create unique index document_transfer_receipts_active_target_uq
    on document_transfer_receipts (target_id) where unlinked_at is null;
create index document_transfer_receipts_document_idx
    on document_transfer_receipts (document_id) where unlinked_at is null;
