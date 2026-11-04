-- Gazetka (plik „UTRZYMYWANIE CEN Gazetka nr 44 2026 + ceny.xlsx” z centrali): wgrywa się ją w „Opracowaniu
-- gazetki”, gdzie każdy EAN z gazetki dostaje artykuł z kartoteki, a z pozycji bez kodu MEA (puste albo DDD) powstają
-- pliki .PRO z cenami promocji i pliki etykiet. Każde wgranie to osobny wpis; ponowne wgranie gazetki o tym samym
-- roku i tygodniu daje poprzedniemu znacznik replaced_at/by (nic nie znika).
create table promo_leaflets (
    id          uuid primary key default gen_random_uuid(),
    store_id    uuid         not null references stores (id),
    year        smallint     not null, -- ROK
    week        smallint     not null, -- TYDZIEŃ (numer gazetki)
    file_name   varchar(255) not null,
    line_count  integer      not null,
    uploaded_by uuid references users (id) on delete set null,
    uploaded_at timestamptz  not null default now(),
    replaced_by uuid references users (id) on delete set null,
    replaced_at timestamptz
);

create unique index promo_leaflets_active on promo_leaflets (store_id, year, week) where replaced_at is null;
create index promo_leaflets_store on promo_leaflets (store_id, uploaded_at desc) where replaced_at is null;

-- Pozycja gazetki tak, jak przyszła (kolumny z nazwami z pliku w komentarzach). Wiersze nagłówków działów
-- („PRODUKTY KLUCZOWE”) nie są pozycjami, ich nazwa trafia do section kolejnych pozycji.
create table promo_leaflet_lines (
    id           uuid primary key default gen_random_uuid(),
    leaflet_id   uuid           not null references promo_leaflets (id),
    line_no      integer        not null, -- LP
    section      varchar(200),            -- dział z wiersza nagłówka nad pozycją
    date_from    date           not null, -- DATA POCZĄTKU
    date_to      date           not null, -- DATA KOŃCA
    mea_code     varchar(20),             -- NR MEA: puste albo DDD = pozycja do opracowania w sklepie
    itm8         varchar(13),             -- ITM 8
    promo_itm8   varchar(13),             -- ITM 8 PROMO
    ean          varchar(20)    not null, -- EANY: kod z gazetki (bywa kodem wagi, EAN-8 albo z zerami z przodu)
    name         varchar(500)   not null, -- PRODUKT
    brand        varchar(200),            -- MARKA
    promo_form   text,                    -- FORMA PROMOCJI
    mega_kind    varchar(100),            -- RODZAJ MEGAPROMOCJI
    client_price varchar(50),             -- CENA DLA KLIENTA (cena sztuki w megapromocji, tekst z pliku)
    price        numeric(12, 4),          -- CENY DO SPRAWDZENIA…: cena gazetkowa brutto; od -1 do 0 = rabat (-0,2 = -20%)
    shelf_label  varchar(300),            -- OZNACZENIE PRZY PÓŁCE: rodzaj cenówki
    constraint promo_leaflet_lines_no unique (leaflet_id, line_no)
);

create index promo_leaflet_lines_mea on promo_leaflet_lines (mea_code) where mea_code is not null;

-- Artykuł z kartoteki wybrany ręcznie dla EAN-u z gazetki (ean bez zer wiodących), zapamiętany w sklepie na kolejne
-- gazetki. Zmiana i usunięcie to znacznik deleted_at/by; previous_id to przypięcie zastąpione tym wpisem (usunięcie
-- wpisu przywraca poprzednie, więc „Cofnij” wraca do stanu sprzed podmiany).
create table promo_leaflet_links (
    id          uuid primary key default gen_random_uuid(),
    store_id    uuid        not null references stores (id),
    ean         varchar(20) not null,
    product_id  uuid        not null references products (id),
    previous_id uuid references promo_leaflet_links (id),
    created_by  uuid references users (id) on delete set null,
    created_at  timestamptz not null default now(),
    deleted_by  uuid references users (id) on delete set null,
    deleted_at  timestamptz
);

create unique index promo_leaflet_links_active on promo_leaflet_links (store_id, ean) where deleted_at is null;
