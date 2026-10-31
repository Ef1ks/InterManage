-- Wprowadzanie danych: propozycje nazwy, marki, nomenklatury i jednostki dla nowych artykułów z faktur KSeF.

-- Podobne nazwy z kartoteki (pg_trgm, word_similarity) - pierwsza warstwa propozycji, bez modelu.
create extension if not exists pg_trgm;
create index products_name_trgm on products using gin (name gin_trgm_ops);

-- Każda propozycja z "Uzupełnij z AI": co wskazała warstwa bez modelu, co odpowiedział model, co trafiło do tabeli
-- i co użytkownik wyeksportował. Służy do mierzenia trafności; nic nie jest usuwane.
create table data_entry_suggestions (
    id uuid primary key default gen_random_uuid(),
    store_id uuid not null references stores (id),
    ksef_invoice_id uuid references ksef_invoices (id),
    ean varchar(14),
    invoice_name text not null,
    invoice_unit varchar(20),
    -- Warstwa bez modelu: zwycięzca głosowania podobnych nazw i najczęstsza podrodzina producenta (prefiks EAN).
    names_subfamily varchar(6),
    producer_subfamily varchar(6),
    producer_products integer,
    producer_share numeric(5, 4),
    certain boolean not null,
    -- Odpowiedź modelu; model_ok = false, gdy model nie odpowiedział (propozycje tylko z warstwy bez modelu).
    model varchar(60),
    model_ok boolean not null,
    model_name text,
    model_brand text,
    model_subfamily varchar(6),
    model_alternatives text[],
    model_confidence numeric(4, 3),
    model_unit varchar(4),
    model_net_content numeric(12, 3),
    -- Co trafiło do tabeli: CERTAIN (bez modelu), AGREED (model zgodny, pewny) albo TO_CHECK (bez podrodziny,
    -- do wyboru z propozycji) i do 5 propozycji podrodziny.
    status varchar(20) not null,
    proposals text[] not null,
    name text,
    brand text,
    subfamily varchar(6),
    unit varchar(4),
    net_content numeric(12, 3),
    -- Wartości z tabeli przy ostatnim eksporcie do Excela (co ludzie poprawiają).
    final_name text,
    final_brand text,
    final_subfamily varchar(6),
    final_unit varchar(4),
    final_net_content numeric(12, 3),
    exported_at timestamp with time zone,
    exported_by uuid references users (id) on delete set null,
    created_by uuid references users (id) on delete set null,
    created_at timestamp with time zone not null default now()
);

create index data_entry_suggestions_store_ean on data_entry_suggestions (store_id, ean);
