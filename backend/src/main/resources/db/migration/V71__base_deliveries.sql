-- Dokument dostawy z bazy (plik .xls z systemu bazy, jeden bon dostawy, np. 09516050082.xls): wgrywa się go
-- w „Dostawie z bazy”, a tabela jak we wprowadzaniu danych otwiera wybrane nośniki. Każde wgranie to osobny wpis;
-- ponowne wgranie tego samego bonu daje poprzedniemu znacznik replaced_at/by (nic nie znika), więc aktywny jest
-- najwyżej jeden wpis na bon w sklepie.
create table base_deliveries (
    id            uuid primary key default gen_random_uuid(),
    store_id      uuid         not null references stores (id),
    number        varchar(20)  not null,
    delivery_date date         not null,
    file_name     varchar(255) not null,
    line_count    integer      not null,
    uploaded_by   uuid references users (id) on delete set null,
    uploaded_at   timestamptz  not null default now(),
    replaced_by   uuid references users (id) on delete set null,
    replaced_at   timestamptz
);

create unique index base_deliveries_active on base_deliveries (store_id, number) where replaced_at is null;
create index base_deliveries_store_date on base_deliveries (store_id, delivery_date desc) where replaced_at is null;

-- Linia pliku tak, jak przyszła (kolumny z nazwami z pliku w komentarzach). Ilość przyjęta w sztukach to
-- coli_prepared × pack; linia z 0 przygotowanych kartonów też jest zapisywana (towar nie przyjechał).
create table base_delivery_lines (
    id               uuid primary key default gen_random_uuid(),
    delivery_id      uuid           not null references base_deliveries (id),
    line_no          integer        not null,
    carrier          varchar(20)    not null, -- NrNosnika
    order_number     varchar(20),             -- Nr_Zam_ITM
    itm8             varchar(13)    not null, -- ITM8 (8 cyfr z pliku)
    name             varchar(200)   not null, -- Nazwa_Produktu
    brand            varchar(100),            -- Marka
    pack             numeric(12, 3) not null, -- PAK: sztuk w kartonie
    deposit          varchar(3),              -- Kaucja: NIE, PLA (PET), PUS (puszka), SZK (szkło)
    best_before      date,                    -- Data_DLV, 0000-00-00 = brak
    days_for_store   integer,                 -- Dni_dla_Sklepu: ile dni terminu ma zostać sklepowi
    ean              varchar(14),             -- EAN, same zera = brak
    pkwiu            varchar(20),
    supplier_code    varchar(10)    not null, -- Spolka: 538 albo 88110 (alkohol)
    mea_code         varchar(20),             -- Kod_MEA
    coli_ordered     numeric(12, 3) not null, -- Coli_Do_Przyg: kartony zamówione
    coli_prepared    numeric(12, 3) not null, -- Coli_Przyg: kartony przygotowane
    purchase_price   numeric(12, 4) not null, -- C_Cesja: cena zakupu netto sztuki
    retail_price     numeric(12, 2),          -- C_Publi: cena sprzedaży brutto dla klienta
    vat_rate         numeric(5, 2)  not null, -- VAT w procentach
    vat_value        numeric(14, 2) not null, -- Kwota_VAT
    net_value        numeric(14, 2) not null, -- Kwota_HT
    gross_value      numeric(14, 2) not null, -- Kwota_TTC
    region_code      varchar(2),              -- Rejon
    family_code      varchar(2),              -- Rodz
    subfamily_code   varchar(2),              -- Pod_Rodz
    sale_content     numeric(12, 3),          -- Typ_Sprz: zawartość sztuki (0.5 przy 0,5 L)
    sale_unit        varchar(2),              -- Jed_Sprz: U sztuka, K kilogram, L litr
    cn               varchar(10),
    gtu              varchar(10),
    constraint base_delivery_lines_no unique (delivery_id, line_no)
);

-- Kody MEA, które baza nadaje na bieżąco: raz wpisany w Mercalys kod nie wraca na listę do wpisania. Kod trafia
-- tu dopiero po potwierdzeniu wpisania (paczka = mea_confirmations). Cofnięcie potwierdzenia to znacznik na
-- potwierdzeniu i jego kodach; aktywny jest najwyżej jeden wpis kodu w sklepie.
create table mea_confirmations (
    id           uuid primary key default gen_random_uuid(),
    store_id     uuid        not null references stores (id),
    confirmed_by uuid references users (id) on delete set null,
    confirmed_at timestamptz not null default now(),
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz
);

create index mea_confirmations_store on mea_confirmations (store_id, confirmed_at desc);

create table mea_codes (
    id              uuid primary key default gen_random_uuid(),
    store_id        uuid        not null references stores (id),
    confirmation_id uuid        not null references mea_confirmations (id),
    code            varchar(20) not null,
    deleted_at      timestamptz
);

create unique index mea_codes_active on mea_codes (store_id, code) where deleted_at is null;
create index mea_codes_confirmation on mea_codes (confirmation_id);
