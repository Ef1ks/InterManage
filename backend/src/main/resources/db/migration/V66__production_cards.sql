-- Karty produkcji garmażu z czasem schładzania (CCP1), dotąd prowadzone w arkuszu Google (moduł deliproduction).
-- Pracownik drukuje formularz z artykułami bazy (nazwa, puste pole na ilość, kod kreskowy), zaznacza na nim, co
-- wyprodukował, skanuje kody i wpisuje ilości. Z tego powstaje karta produkcji do wydruku, także w sklepach
-- zaprzyjaźnionych i przy ruchu międzymagazynowym (MM), którym wyroby jadą do innego sklepu.

-- Schemat parametrów procesu: artykuły garmażu mają kilka powtarzających się zestawów (pieczenie 140°C, temperatura
-- wewnętrzna 75/80/85°C zależnie od mięsa, schładzanie 20 do 4°C, ważność 3 lub 5 dni), więc artykuł wybiera schemat
-- zamiast wpisywać wszystko osobno. Puste = na karcie "-". Usunięcie to znacznik.
create table production_schemas (
    id                   uuid primary key default gen_random_uuid(),
    store_id             uuid         not null references stores (id),
    name                 varchar(100) not null,
    baking_temperature   int,
    internal_temperature int,
    cooling_time         int,
    cooling_temperature  int,
    shelf_life_days      int,
    created_by           uuid references users (id) on delete set null,
    created_at           timestamptz  not null default now(),
    updated_by           uuid references users (id) on delete set null,
    updated_at           timestamptz  not null default now(),
    deleted_by           uuid references users (id) on delete set null,
    deleted_at           timestamptz
);

create unique index production_schemas_name_uq
    on production_schemas (store_id, upper(name)) where deleted_at is null;

-- Artykuł bazy kart produkcji: kod (EAN z kartoteki, kod z etykiety wagi albo kod wewnętrzny, drukowany na
-- formularzu i skanowany), nazwa na karcie i schemat. Kod aktywny raz w sklepie; artykuł bez kodu wybiera się
-- z listy po nazwie.
create table production_articles (
    id           uuid primary key default gen_random_uuid(),
    store_id     uuid         not null references stores (id),
    code         varchar(20),
    name         varchar(200) not null,
    nomenclature varchar(20),
    schema_id    uuid         not null references production_schemas (id),
    created_by   uuid references users (id) on delete set null,
    created_at   timestamptz  not null default now(),
    updated_by   uuid references users (id) on delete set null,
    updated_at   timestamptz  not null default now(),
    deleted_by   uuid references users (id) on delete set null,
    deleted_at   timestamptz
);

create unique index production_articles_code_uq
    on production_articles (store_id, code) where deleted_at is null and code is not null;

-- Pieczątka zakładu w nagłówku karty (kilka linii), per sklep. Bez wpisu: nazwa sklepu i NIP.
create table production_card_settings (
    store_id   uuid primary key references stores (id),
    stamp      text        not null,
    updated_by uuid references users (id) on delete set null,
    updated_at timestamptz not null default now()
);

-- Karta produkcji z dnia. Kilka kart jednego dnia jest dozwolonych. Usunięcie to znacznik.
create table production_cards (
    id              uuid primary key default gen_random_uuid(),
    store_id        uuid        not null references stores (id),
    production_date date        not null,
    created_by      uuid references users (id) on delete set null,
    created_at      timestamptz not null default now(),
    updated_by      uuid references users (id) on delete set null,
    updated_at      timestamptz not null default now(),
    deleted_by      uuid references users (id) on delete set null,
    deleted_at      timestamptz
);

create index production_cards_store_date_idx on production_cards (store_id, production_date);

-- Pozycja karty: kopia nazwy, kodu i parametrów schematu z chwili dodania (późniejsza zmiana bazy nie zmienia
-- zapisanych kart), ilość i godzina rozpoczęcia procesu (zakończenie i przekazanie do schłodzenia godzinę później).
-- Ważność liczy się z daty produkcji i shelf_life_days.
create table production_card_lines (
    id                   uuid primary key default gen_random_uuid(),
    card_id              uuid           not null references production_cards (id),
    position             int            not null,
    article_id           uuid           not null references production_articles (id),
    code                 varchar(20),
    name                 varchar(200)   not null,
    quantity             numeric(12, 3) not null,
    start_hour           int            not null,
    baking_temperature   int,
    internal_temperature int,
    cooling_time         int,
    cooling_temperature  int,
    shelf_life_days      int
);

create index production_card_lines_card_idx on production_card_lines (card_id, position);

-- Karty produkcji przypięte do ruchu międzymagazynowego (MM): sklep docelowy widzi je i drukuje przy dokumencie
-- do wprowadzenia. Odpięcie to znacznik, ponowne przypięcie to nowy wpis.
create table document_production_cards (
    id          uuid primary key default gen_random_uuid(),
    document_id uuid        not null references documents (id),
    card_id     uuid        not null references production_cards (id),
    linked_by   uuid references users (id) on delete set null,
    linked_at   timestamptz not null default now(),
    unlinked_by uuid references users (id) on delete set null,
    unlinked_at timestamptz
);

create unique index document_production_cards_active_uq
    on document_production_cards (document_id, card_id) where unlinked_at is null;
create index document_production_cards_card_idx on document_production_cards (card_id) where unlinked_at is null;
