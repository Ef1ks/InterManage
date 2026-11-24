-- Makra Mercalys zlecane z przeglądarki na wolnym komputerze sklepu (agent sklepu). Serwer składa makro, wybiera
-- komputer z działającym agentem i odtwarzaczem (najdłużej nieużywany), a agent pokazuje odliczanie z „Anuluj”;
-- anulowanie przekazuje zlecenie kolejnemu komputerowi. Wiersze zostają jako historia.
create table agent_macro_jobs (
    id uuid primary key,
    store_id uuid not null references stores (id),
    kind varchar(40) not null,
    title varchar(200) not null,
    macro text not null,
    item_count integer not null default 0,
    status varchar(20) not null,
    -- Komputer wybrany ręcznie (bez niego dowolny wolny).
    target_agent_id uuid references store_agents (id),
    -- Komputer, któremu zlecenie zaproponowano albo który je wykonuje.
    agent_id uuid references store_agents (id),
    declined_agent_ids uuid[] not null default '{}',
    offered_at timestamptz,
    started_at timestamptz,
    finished_at timestamptz,
    result_message text,
    result_codes text[] not null default '{}',
    created_by uuid not null references users (id),
    created_at timestamptz not null,
    cancelled_by uuid references users (id)
);

create index agent_macro_jobs_store on agent_macro_jobs (store_id, created_at desc);

-- Najwyżej jedno makro naraz w sklepie (Mercalys jest jeden).
create unique index agent_macro_jobs_active_uq on agent_macro_jobs (store_id)
    where status in ('OFFERED', 'RUNNING');
