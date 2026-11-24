-- Plan cadencier (cad_planing_RRRR_pdv*.xlsx) i pliki TOP z portalu Grupy Muszkieterów (/api/cad/, sekcja top).
-- Plik TOP jest jeden na rejon logistyczny (Swadzim, Dąbrowa), więc wspólny dla sklepów rejonu. Portal wystawia go
-- kilka dni przed wejściem cadencier, dlatego o tym, który obowiązuje, decyduje plan: najpóźniejszy numer (nr_cad),
-- którego data początku już minęła.

-- Plan roku; nowszy plan tego samego roku daje poprzedniemu znacznik replaced_at.
create table cad_plans (
    id          uuid primary key default gen_random_uuid(),
    year        smallint     not null,
    file_name   varchar(300) not null,
    sha256      varchar(64)  not null,
    -- Data zapisu skoroszytu w Excelu (docProps/core.xml): po niej starszy plan nie zastąpi nowszego.
    modified_at timestamptz,
    entry_count integer      not null,
    -- Ścieżka w portalu; pusta = plan wgrany ręcznie.
    source_path varchar(300),
    uploaded_by uuid references users (id),
    uploaded_at timestamptz  not null default now(),
    replaced_by uuid references users (id),
    replaced_at timestamptz
);

create unique index cad_plans_active on cad_plans (year) where replaced_at is null;

-- nr_cad pusty = „zmiany ręczne” (dzień bez pliku TOP). Daty końca są tylko z pliku: raz to dzień wejścia następnego
-- numeru, raz dzień przed nim, więc koniec liczymy z początku kolejnego.
create table cad_plan_entries (
    id        uuid primary key default gen_random_uuid(),
    plan_id   uuid    not null references cad_plans (id),
    lp        integer not null,
    nr_cad    varchar(20),
    starts_on date    not null,
    ends_on   date,
    comment   varchar(300)
);

create index cad_plan_entries_plan_idx on cad_plan_entries (plan_id);

-- Ostatnie sprawdzenie planu roku w portalu (nazwa pliku jest stała, planu nie ma na liście API); wiersz nadpisywany.
create table cad_plan_checks (
    year       smallint primary key,
    checked_at timestamptz not null,
    error      varchar(500)
);

-- Rejon logistyczny sklepu: wynika z tego, który plik TOP portal pokazuje sklepowi. Wiersz nadpisywany.
create table cad_store_regions (
    store_id   uuid primary key references stores (id),
    region     varchar(40) not null,
    updated_at timestamptz not null
);

create table cad_top_files (
    id                uuid primary key default gen_random_uuid(),
    region            varchar(40)  not null,
    nr_cad            varchar(20)  not null,
    file_name         varchar(300) not null,
    source_path       varchar(300) not null,
    -- Data zmiany w portalu, tak jak ją podał (do porównania i kolejności).
    source_updated_at varchar(30)  not null,
    sha256            varchar(64)  not null,
    article_count     integer      not null,
    downloaded_at     timestamptz  not null,
    replaced_at       timestamptz
);

create unique index cad_top_files_active on cad_top_files (region, nr_cad) where replaced_at is null;
create unique index cad_top_files_source on cad_top_files (region, source_path, source_updated_at);

-- Artykuły pliku TOP. ITM8 i EAN dopełnione zerami do 13 znaków, jak w kartotece.
create table cad_top_articles (
    file_id          uuid         not null references cad_top_files (id),
    itm8             varchar(13)  not null,
    ean              varchar(14),
    name             varchar(200) not null,
    brand            varchar(100),
    supplier_code    varchar(20),
    -- E marka producenta, MP PNM polski, M PNM INT międzynarodowy, F PNM Francja, FP PNM Top Budget.
    product_type     varchar(10),
    purchase_price   numeric(12, 4),
    sale_price       numeric(12, 2),
    -- Sztuk w colis.
    pack             integer,
    vat_percent      numeric(5, 2),
    -- BTop najbardziej porównywalne cenowo w konkurencji, B bardzo porównywalne, A PNM w indeksie sklepów; puste = poza.
    top_mark         varchar(10),
    subfamily_code   varchar(6),
    category         varchar(20),
    -- Nowość względem poprzedniego CAD / InterCAD.
    is_new           boolean      not null,
    -- Marża z pliku w procentach (od sprzedaży brutto) i rotacja w sieci; puste = brak w pliku.
    margin_percent   numeric(9, 2),
    network_rotation numeric(14, 4),
    primary key (file_id, itm8)
);

create index cad_top_articles_itm8_idx on cad_top_articles (itm8);
create index cad_top_articles_ean_idx on cad_top_articles (ean);
