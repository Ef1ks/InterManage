-- Zużycie tokenów modeli AI: wiersz na każde udane zapytanie do modelu (propozycje we wprowadzaniu danych, skracanie
-- nazw, odczyt skanu WZ mięsa), z modelem z odpowiedzi i liczbami tokenów z usageMetadata. Sklep i osoba z żądania,
-- które o to zapytało (puste, gdy zapytanie nie szło z żądania sklepu). Historia, wiersze się nie zmieniają.
create table ai_usage (
    id             uuid primary key,
    store_id       uuid references stores (id),
    user_id        uuid references users (id) on delete set null,
    feature        varchar(40)  not null,
    model          varchar(100) not null,
    prompt_tokens  integer      not null,
    output_tokens  integer      not null,
    thought_tokens integer      not null,
    cached_tokens  integer      not null,
    total_tokens   integer      not null,
    created_at     timestamptz  not null
);

create index ai_usage_store_created_idx on ai_usage (store_id, created_at);
