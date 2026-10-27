-- Nomenklatura sklepu i dzienna sprzedaż artykułów, pod raporty sprzedaży.

-- Nomenklatura z raportu "LISTA NOMENKLATUR" systemu sklepu (PDF, np. nomenklatury.pdf, ok. 2400 linii).
-- Trzy poziomy, kod rośnie o dwie cyfry na poziom: rejon "01" (01_DELIKATESY) → rodzina "0101"
-- (PIECZYWO CHRUPKIE) → podrodzina "010101" (PIECZYWO TWARDE). Produkt należy do podrodziny:
-- products.nomenclature "01 01 01" to kod "010101". Pozycji, których zabrakło w nowym raporcie, nie usuwamy.
create table nomenclature_imports (
    id                  uuid primary key default gen_random_uuid(),
    store_id            uuid         not null references stores (id),
    file_name           varchar(255) not null,
    -- "lun. 26/10/2026 - 14:43:41" z nagłówka raportu.
    report_generated_at timestamp,
    imported_by         uuid         references users (id) on delete set null,
    imported_at         timestamptz  not null default now(),
    total_rows          integer      not null,
    created_count       integer      not null,
    updated_count       integer      not null,
    missing_count       integer      not null
);

create index nomenclature_imports_store_idx on nomenclature_imports (store_id, imported_at desc);

create table nomenclatures (
    id             uuid primary key default gen_random_uuid(),
    store_id       uuid         not null references stores (id),
    code           varchar(6)   not null, -- Kod Nomenklatury, bez spacji
    level          varchar(10)  not null, -- REGION (2 cyfry), FAMILY (4), SUBFAMILY (6)
    parent_id      uuid         references nomenclatures (id), -- rodzina dla podrodziny, rejon dla rodziny
    -- Kody przodków (i własny) na każdym poziomie, żeby raport grupował po rejonie lub rodzinie bez rekurencji.
    region_code    varchar(2)   not null generated always as (left(code, 2)) stored,
    family_code    varchar(4)   generated always as (case when length(code) >= 4 then left(code, 4) end) stored,
    name           varchar(100) not null, -- Nazwa Nomenklatury
    vat_code       smallint,              -- Kod VAT (kod stawki z systemu sklepu, nie procent)
    unit           varchar(10),           -- Jednostka miary (kg, L, szt., m2, m, W)
    label_count    smallint,              -- Ilość etyk.
    label_format   smallint,              -- "Gondola" Format etykiet
    till_mode      varchar(30),           -- Typ Przejść do Kas (Podać cenę)
    sale_type      varchar(30),           -- Typ sprzedaży (Samoobsługa, Tradycyjny)
    stock_mode     varchar(30),           -- St.Mag./Reappro (St.Mag., Bez zarzadzania)
    last_import_id uuid         not null references nomenclature_imports (id),
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now(),
    check (code ~ '^([0-9]{2}){1,3}$'),
    check (level = case length(code) when 2 then 'REGION' when 4 then 'FAMILY' else 'SUBFAMILY' end),
    check ((level = 'REGION') = (parent_id is null))
);

create unique index nomenclatures_store_code_uq on nomenclatures (store_id, code);
create index nomenclatures_parent_idx on nomenclatures (parent_id);

-- Kody nomenklatury produktu na trzech poziomach, liczone przez bazę z products.nomenclature ("01 01 01"),
-- więc import kartoteki nic tu nie zmienia, a raport łączy produkt z nomenklaturą po (store_id, kod).
alter table products
    add column region_code    varchar(2) generated always as (nullif(left(replace(nomenclature, ' ', ''), 2), '')) stored,
    add column family_code    varchar(4) generated always as (
        case when length(replace(nomenclature, ' ', '')) >= 4 then left(replace(nomenclature, ' ', ''), 4) end) stored,
    add column subfamily_code varchar(6) generated always as (
        case when length(replace(nomenclature, ' ', '')) = 6 then replace(nomenclature, ' ', '') end) stored;

create index products_store_region_idx on products (store_id, region_code);
create index products_store_family_idx on products (store_id, family_code);
create index products_store_subfamily_idx on products (store_id, subfamily_code);

-- Produkt z pełną ścieżką nomenklatury (nazwy rejonu, rodziny i podrodziny) do raportów.
-- Left join: produkt bez nomenklatury albo z kodem spoza listy też tu jest, z pustymi nazwami.
create view product_nomenclature as
select p.id             as product_id,
       p.store_id,
       p.region_code,
       r.id             as region_id,
       r.name           as region_name,
       p.family_code,
       f.id             as family_id,
       f.name           as family_name,
       p.subfamily_code,
       s.id             as subfamily_id,
       s.name           as subfamily_name
from products p
left join nomenclatures r on r.store_id = p.store_id and r.code = p.region_code
left join nomenclatures f on f.store_id = p.store_id and f.code = p.family_code
left join nomenclatures s on s.store_id = p.store_id and s.code = p.subfamily_code;

-- Sprzedaż z raportu "Zestawienie: Wejścia/Wyjścia" (poziom szczegółu: wg artykułu, zakres: wg dnia,
-- ruch: sprzedaż po rabacie, wycena FIFO), np. sprzedaz.xlsx. Okres z "Wybór Danych: Od 31/12/2025 do 30/06/2026".
-- Na start ok. 2 mln wierszy, potem 15-20 tys. dziennie, więc import idzie paczkami jednym zapytaniem (SaleRepository).
-- Raz zapisana sprzedaż jest jedynym źródłem prawdy: kolejne raporty tylko dopisują dni, których jeszcze nie ma.
create table sales_imports (
    id                  uuid primary key default gen_random_uuid(),
    store_id            uuid         not null references stores (id),
    file_name           varchar(255) not null,
    -- "Data: 26/10/2026   Godzina: 15:03:27" z nagłówka raportu.
    report_generated_at timestamp,
    period_from         date         not null,
    period_to           date         not null,
    imported_by         uuid         references users (id) on delete set null,
    imported_at         timestamptz  not null default now(),
    total_rows          integer      not null,
    created_count       integer      not null, -- dopisane dni sprzedaży artykułów
    existing_count      integer      not null, -- były już w bazie, pominięte
    -- Z tych pominiętych: plik ma inne wartości niż zapisane (zostają zapisane, plik się z nimi nie zgadza).
    conflict_count      integer      not null,
    check (period_from <= period_to)
);

create index sales_imports_store_idx on sales_imports (store_id, imported_at desc);

-- Jeden wiersz = sprzedaż jednego artykułu (ITM8) w jednym dniu. Wierszy są miliony, więc tabela jest wąska:
-- klucz naturalny zamiast uuid, bez znaczników czasu (kiedy i z jakiego pliku, mówi import_id).
-- Produkt to products (store_id, itm8), bez klucza obcego, żeby sprzedaż artykułu spoza kartoteki nie blokowała
-- importu. Para ITM8 + EAN: ean to "EAN Prio" z raportu (product_eans).
create table sales (
    store_id         uuid           not null references stores (id),
    sale_date        date           not null, -- Data
    itm8             varchar(13)    not null, -- ITM8 Prio
    ean              varchar(14),             -- EAN Prio
    quantity         numeric(14, 3) not null, -- Ilość (sztuki albo kg, zwroty na minusie)
    cost_value       numeric(14, 2) not null, -- Wartosc FIFO (koszt zakupu netto)
    sale_value_gross numeric(14, 2) not null, -- Wartość ceny sprzedaży (brutto, po rabacie)
    vat_value        numeric(14, 2) not null, -- Wartość VAT
    sale_value_net   numeric(14, 2) not null generated always as (sale_value_gross - vat_value) stored,
    margin_value     numeric(14, 2) not null, -- Wart.Marza FIFO (sprzedaż netto - koszt)
    margin_percent   numeric(9, 2),           -- % Marża, jak w raporcie
    import_id        uuid           not null references sales_imports (id), -- import, który zapisał wiersz
    -- Raporty za okres: zakres po (sklep, data); ten sam klucz rozstrzyga, czy dzień artykułu już jest.
    primary key (store_id, sale_date, itm8)
);

-- Historia sprzedaży jednego artykułu.
create index sales_store_itm8_date_idx on sales (store_id, itm8, sale_date);

-- Zapisanej sprzedaży nie da się zmienić ani usunąć, także poza aplikacją. Wyzwalacz na poziomie instrukcji
-- nie kosztuje nic przy imporcie (dotyczy tylko UPDATE, DELETE i TRUNCATE).
create function sales_read_only() returns trigger language plpgsql as $$
begin
    raise exception 'Zapisanej sprzedaży nie można zmieniać ani usuwać (%)', tg_op
        using errcode = 'restrict_violation';
end
$$;

create trigger sales_read_only
    before update or delete or truncate on sales
    for each statement execute function sales_read_only();
