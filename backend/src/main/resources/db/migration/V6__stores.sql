-- Sklepy (punkty sprzedaży Intermarché). Numer pochodzi z systemu sklepu i jest w nagłówku raportów
-- ("Numer punktu sprzedaży 10023"). Sklepów nie usuwamy, tylko wyłączamy, bo mają historię importów.
create table stores (
    id         uuid primary key default gen_random_uuid(),
    number     varchar(20)  not null,
    name       varchar(100) not null,
    address    varchar(255),
    active     boolean      not null default true,
    created_at timestamptz  not null default now()
);

create unique index stores_number_uq on stores (number);

-- Do których sklepów ma dostęp użytkownik. Właściciel widzi wszystkie sklepy bez wpisów tutaj.
create table user_stores (
    user_id  uuid   not null references users (id) on delete cascade,
    store_id uuid   not null references stores (id),
    primary key (user_id, store_id)
);

create index user_stores_store_id_idx on user_stores (store_id);

-- Dane zaimportowane przed wprowadzeniem sklepów pochodzą z raportu sklepu 10023.
insert into stores (number, name)
select '10023', 'Sklep 10023' where exists (select 1 from product_imports);

alter table product_imports add column store_id uuid   references stores (id);
update product_imports set store_id = (select id from stores where number = '10023');
alter table product_imports alter column store_id set not null;
create index product_imports_store_id_idx on product_imports (store_id, imported_at desc);

-- Kartoteka jest osobna dla każdego sklepu: ten sam ITM8 może być w kilku sklepach z innymi cenami i stanami.
alter table products add column store_id uuid   references stores (id);
update products set store_id = (select id from stores where number = '10023');
alter table products alter column store_id set not null;
drop index products_itm8_uq;
create unique index products_store_itm8_uq on products (store_id, itm8);
