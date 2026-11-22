-- Zgłoszenia poprawek i błędów od pracowników (przycisk w prawym górnym rogu aplikacji) ze zrzutami ekranu. Czyta je
-- i prowadzi tylko właściciel (Ustawienia → Zgłoszenia). Treść zrzutów w bazie, nie w publicznym R2: zrzuty pokazują
-- sprzedaż i marże. Usunięcie zgłoszenia i zrzutu to znacznik.
create table feedback_reports (
    id                uuid primary key,
    store_id          uuid references stores (id),
    kind              text        not null check (kind in ('BUG', 'IMPROVEMENT')),
    description       text        not null,
    page_url          text,
    page_label        text,
    user_agent        text,
    status            text        not null check (status in ('NEW', 'IN_PROGRESS', 'DONE', 'REJECTED')),
    owner_note        text,
    status_changed_by uuid references users (id) on delete set null,
    status_changed_at timestamptz,
    created_by        uuid references users (id) on delete set null,
    created_at        timestamptz not null,
    deleted_by        uuid references users (id) on delete set null,
    deleted_at        timestamptz
);

create index feedback_reports_status_idx on feedback_reports (status, created_at desc) where deleted_at is null;
create index feedback_reports_creator_idx on feedback_reports (created_by, created_at desc) where deleted_at is null;

create table feedback_report_attachments (
    id           uuid primary key,
    report_id    uuid        not null references feedback_reports (id),
    file_name    text        not null,
    content_type text        not null,
    size_bytes   int         not null,
    content      bytea       not null,
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz not null
);

create index feedback_report_attachments_report_idx on feedback_report_attachments (report_id, created_at);
