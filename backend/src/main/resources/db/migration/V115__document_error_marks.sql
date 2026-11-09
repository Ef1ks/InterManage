-- Przyjęcie PZ oznaczone jako błąd (np. wprowadzone dwa razy albo do złego dostawcy): znika z listy dokumentów,
-- liczników i raportów, ale zostaje w bazie i pod swoim adresem. Cofnięcie czyści znacznik.
alter table documents
    add column error_marked_at timestamptz,
    add column error_marked_by uuid references users (id) on delete set null;

-- Listy i raporty pomijają oznaczone, a filtr „Oznaczone jako błąd” szuka tylko ich.
create index documents_error_marked_idx on documents (store_id, type) where error_marked_at is not null;
