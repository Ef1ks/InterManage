-- Dziennik sklepu dla kierowników: każda zmiana z API (kto, kiedy, w którym sklepie, co, z jakim wynikiem) i ostrzeżenia
-- oraz błędy z logów backendu (SLF4J) z kontekstem sklepu. Wiersze się nie zmieniają i nie są usuwane (historia).
-- Bez kluczy obcych: zdarzenia zapisuje bufor w tle, także po powrocie bazy z pliku na dysku, i nie może go zatrzymać
-- wpis wskazujący sklep z adresu żądania, którego nie ma.
create table store_events (
    id          uuid primary key,
    occurred_at timestamptz  not null,
    store_id    uuid,
    user_id     uuid,
    kind        varchar(20)  not null,
    level       varchar(10)  not null,
    module      varchar(40),
    method      varchar(10),
    path        varchar(500),
    status      integer,
    duration_ms integer,
    summary     varchar(500) not null,
    detail      text
);

create index store_events_store_time_idx on store_events (store_id, occurred_at desc, id desc);
