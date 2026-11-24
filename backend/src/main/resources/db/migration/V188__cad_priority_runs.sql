-- Ustawienie ITM8 z cadencier jako priorytetowego na karcie artykułu w Mercalys (makro albo ręcznie). Wpis mówi tylko,
-- że to zlecono: wynik widać w następnym raporcie kartoteki (artykuł znika z listy albo wraca jako nieustawiony).
create table cad_priority_runs (
    id         uuid primary key default gen_random_uuid(),
    store_id   uuid        not null references stores (id),
    -- ITM8 z pliku TOP, który ma być priorytetowy, i ITM8 karty artykułu w sklepie z tej chwili.
    top_itm8   varchar(13) not null,
    itm8       varchar(13) not null,
    ean        varchar(14),
    nr_cad     varchar(20) not null,
    -- MACRO makro do Mercalys, MANUAL oznaczone ręcznie.
    source     varchar(10) not null,
    created_by uuid references users (id),
    created_at timestamptz not null,
    deleted_by uuid references users (id),
    deleted_at timestamptz
);

create index cad_priority_runs_store_idx on cad_priority_runs (store_id, created_at desc) where deleted_at is null;
