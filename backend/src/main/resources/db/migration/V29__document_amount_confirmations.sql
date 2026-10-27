-- Potwierdzona niezgodność kwoty: wartość zakupu netto przyjęcia PZ (po korektach) różni się od sumy netto jego faktur
-- KSeF, a ktoś sprawdził, że tak ma być. Potwierdzenie dotyczy zapisanych kwot: gdy faktury albo przyjęcie się
-- zmienią, różnica wraca do sprawdzenia. Cofnięcie to znacznik (cancelled_at/by), wpis zostaje.
create table document_amount_confirmations (
    id             uuid primary key,
    document_id    uuid           not null references documents (id),
    invoice_net    numeric(16, 2) not null,
    document_value numeric(16, 2) not null,
    confirmed_by   uuid references users (id),
    confirmed_at   timestamptz    not null,
    cancelled_by   uuid references users (id),
    cancelled_at   timestamptz
);

create unique index document_amount_confirmations_active_uq
    on document_amount_confirmations (document_id) where cancelled_at is null;
