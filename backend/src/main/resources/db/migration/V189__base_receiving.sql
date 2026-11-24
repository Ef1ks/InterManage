-- Wprowadzenie dostawy z bazy do Mercalys makrem na komputerze sklepu: zamówienie (nr kodu), na które wszedł plik bonu
-- każdego dostawcy (538, 88110), i tymczasowy bon dostawy (Excel) z Mercalys do „Znajdź różnice”. Kluczem jest numer
-- bonu, nie wgrany dokument, bo dokument bywa wgrany ponownie.
create table base_delivery_orders (
    id uuid primary key,
    store_id uuid not null references stores (id),
    bon_number varchar(20) not null,
    supplier_code varchar(10) not null,
    order_number varchar(20) not null,
    -- Wpisany ręcznie (makro zgubiło numer albo dostawę wprowadził człowiek); bez autora odczytało go makro.
    entered_by uuid references users (id),
    created_at timestamptz not null,
    replaced_at timestamptz,
    -- Dostawa z wpisanym Nr BL zakończona w Mercalys.
    closed_at timestamptz
);

create unique index base_delivery_orders_active_uq on base_delivery_orders (store_id, bon_number, supplier_code)
    where replaced_at is null;

create table base_delivery_notes (
    id uuid primary key,
    store_id uuid not null references stores (id),
    bon_number varchar(20) not null,
    supplier_code varchar(10) not null,
    file_name varchar(255) not null,
    content bytea not null,
    uploaded_at timestamptz not null,
    replaced_at timestamptz
);

create unique index base_delivery_notes_active_uq on base_delivery_notes (store_id, bon_number, supplier_code)
    where replaced_at is null;

-- Czego dotyczy makro (np. numer bonu), żeby wynik trafił we właściwe miejsce.
alter table agent_macro_jobs add column reference varchar(60);
