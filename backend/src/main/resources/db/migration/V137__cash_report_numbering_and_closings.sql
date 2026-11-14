-- Numer dokumentu jak w dotychczasowym arkuszu: rodzaj + kolejny numer w miesiącu daty dokumentu + miesiąc + rok
-- (KP 22/11/2026), osobno dla każdego sklepu. „Za co” = operacja z listy (Utarg z dnia, Zwrot gotówki…)
-- i opis (np. ZDRAPKOMAT). Kontrahent, adres i opis są opcjonalne.
alter table cash_documents add column month integer;
update cash_documents set month = extract(month from issue_date)::integer;
alter table cash_documents alter column month set not null;

alter table cash_documents add column operation varchar(100);
update cash_documents set operation = left(title, 100);
alter table cash_documents alter column operation set not null;

alter table cash_documents alter column title drop not null;
alter table cash_documents alter column counterparty drop not null;

drop index cash_documents_number_uq;
create unique index cash_documents_number_uq on cash_documents (store_id, kind, year, month, sequence_number);

-- Raport dzienny zakończony automatycznie o 23:00 (czas sklepu) za każdy dzień z dokumentami. Numer RK to dzień
-- raportu (RK 5/11/2026). To zrzut: stan poprzedni, obroty, stan obecny i dokumenty z kwotami z chwili zamknięcia,
-- więc podgląd i wydruk są zawsze takie same. Po zamknięciu dokumentów z tego i wcześniejszych dni nie da się
-- wystawić, anulować ani przywrócić, a stanu początkowego zmienić.
create table cash_report_closings (
    id              uuid primary key,
    store_id        uuid           not null references stores (id),
    report_date     date           not null,
    opening_balance numeric(12, 2) not null,
    receipts        numeric(12, 2) not null,
    payments        numeric(12, 2) not null,
    closing_balance numeric(12, 2) not null,
    stamp_id        uuid references cash_stamps (id),
    closed_by       uuid references users (id) on delete set null,
    closed_at       timestamptz    not null
);

create unique index cash_report_closings_day_uq on cash_report_closings (store_id, report_date);

create table cash_report_closing_rows (
    id           uuid primary key,
    closing_id   uuid           not null references cash_report_closings (id),
    position     integer        not null,
    document_id  uuid           not null references cash_documents (id),
    kind         varchar(2)     not null,
    number       varchar(30)    not null,
    amount       numeric(12, 2) not null,
    content      varchar(610)   not null,
    counterparty varchar(200),
    cancelled    boolean        not null,
    balance      numeric(12, 2)
);

create index cash_report_closing_rows_closing_idx on cash_report_closing_rows (closing_id);
