-- Historia zapytań do KSeF (limity liczone w oknach przesuwnych do godziny). Dotąd była tylko w pamięci, więc każdy
-- restart backendu (wdrożenie) ją zerował i aplikacja przekraczała limity KSeF, a KSeF odpowiadał coraz dłuższymi
-- blokadami. Dane techniczne: wpisy starsze niż godzina są usuwane.
create table ksef_api_calls (
    id        uuid primary key,
    nip       varchar(10) not null,
    operation varchar(20) not null,
    called_at timestamptz not null
);

create index ksef_api_calls_nip_operation_called_at_idx on ksef_api_calls (nip, operation, called_at);

-- Blokada z KSeF (HTTP 429, Retry-After) na cały kontekst NIP.
create table ksef_api_blocks (
    nip           varchar(10) primary key,
    blocked_until timestamptz not null
);

-- Numer dokumentu dostawcy w przyjęciu poprawiony ręcznie (np. literówka, przez którą faktura KSeF nie wiąże się
-- z PZ). supplier_document_number to numer obowiązujący, report_supplier_document_number - ten z raportu;
-- kolejne importy zmieniają tylko numer z raportu, dopóki poprawka obowiązuje.
alter table documents add column report_supplier_document_number varchar(60);
update documents set report_supplier_document_number = supplier_document_number;
alter table documents add column supplier_document_number_edited_at timestamptz;
alter table documents add column supplier_document_number_edited_by uuid references users (id);
