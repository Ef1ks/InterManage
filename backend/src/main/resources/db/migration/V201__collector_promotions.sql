-- Promocje ustawione na kolektorze (3 Ustaw promocje): kolektor wysyła je po włożeniu na podstawkę i pytaniu pracownika.
-- W Intermanage wstawia się je do Ustaw promocje albo od razu ustawia makrem na komputerze sklepu (z cenówkami po nim).
-- Id nadaje kolektor, więc ponowiona wysyłka niczego nie dubluje; po zmianach na kolektorze przychodzi nowa wysyłka
-- z samymi zmienionymi artykułami.
create table collector_promotions (
    id              uuid primary key,
    store_id        uuid        not null references stores (id),
    collector_id    uuid        not null references store_collectors (id),
    -- Kiedy zaczęto listę na kolektorze (czas sklepu, zegar kolektora).
    started_at      timestamp,
    received_at     timestamptz not null,
    -- Początek operacji wybrany na kolektorze; pusty = dziś.
    start_date      date,
    end_date        date        not null,
    line_count      integer     not null,
    -- Pracownik wybrał na kolektorze „Wyślij i ustaw w Mercalys”.
    macro_requested boolean     not null,
    macro_job_id    uuid references agent_macro_jobs (id),
    -- Dlaczego makro nie ruszyło (np. makra z przeglądarki wyłączone w sklepie).
    macro_error     varchar(500),
    -- Plik cenówek w kolejce plików sklepu, po udanym makrze.
    labels_file_id  uuid references store_file_queue (id),
    inserted_by     uuid references users (id) on delete set null,
    inserted_at     timestamptz,
    deleted_by      uuid references users (id) on delete set null,
    deleted_at      timestamptz
);

create index collector_promotions_store on collector_promotions (store_id, received_at desc);
create index collector_promotions_job on collector_promotions (macro_job_id);

-- Artykuł z ceną promocji i końcem; nazwa i marka z kartoteki kolektora z chwili skanowania.
create table collector_promotion_lines (
    id           uuid primary key,
    promotion_id uuid          not null references collector_promotions (id),
    position     integer       not null,
    itm8         varchar(13)   not null,
    ean          varchar(14)   not null,
    name         varchar(100)  not null,
    brand        varchar(100)  not null,
    price        numeric(10, 2) not null,
    date_to      date          not null,
    constraint collector_promotion_lines_position unique (promotion_id, position)
);
