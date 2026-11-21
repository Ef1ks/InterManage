-- Hierarchia sklepu: każdy sklep ma własne rangi (role z uprawnieniami) ułożone od najwyższej, a konto ma w każdym
-- sklepie swoją rangę. Globalna rola konta zostaje tylko po to, żeby odróżnić właściciela aplikacji (roles.owner)
-- od zwykłego konta („Konto sklepu”, roles.system_key = 'ACCOUNT', bez uprawnień).
-- Domyślne rangi, przeniesienie dotychczasowych ról i rang kont robi przy starcie StoreRoleBootstrap.

alter table roles add column system_key varchar(40);
create unique index roles_system_key_uq on roles (system_key) where system_key is not null;

insert into roles (name, description, owner, system_key)
values ('Konto sklepu', 'Uprawnienia daje ranga konta w każdym sklepie', false, 'ACCOUNT');

create table store_roles (
    id                uuid primary key,
    store_id          uuid         not null references stores (id),
    -- Ten sam klucz w każdym sklepie (np. MANAGER): po nim stanowiska wskazują rangę, a przesłana hierarchia
    -- łączy się z rangami sklepu zaprzyjaźnionego.
    role_key          varchar(80)  not null,
    name              varchar(100) not null,
    description       varchar(500),
    -- Aderon: wszystkie uprawnienia w sklepie, zawsze na górze, nie da się go usunąć.
    admin             boolean      not null default false,
    -- Poza hierarchią (Księgowość, Kadry): nikt nie stoi pod nimi ani nad nimi poza Aderonem.
    outside_hierarchy boolean      not null default false,
    position          integer      not null,
    created_at        timestamptz  not null,
    created_by        uuid references users (id),
    updated_at        timestamptz  not null,
    deleted_at        timestamptz,
    deleted_by        uuid references users (id)
);

create unique index store_roles_key_uq on store_roles (store_id, role_key) where deleted_at is null;
create unique index store_roles_name_uq on store_roles (store_id, lower(name)) where deleted_at is null;
create index store_roles_store_idx on store_roles (store_id, position);

create table store_role_permissions (
    store_role_id uuid        not null references store_roles (id),
    category      varchar(50) not null,
    level         varchar(20) not null,
    primary key (store_role_id, category)
);

-- Ranga konta w sklepie: jeden wiersz na konto i sklep, nadpisywany przy zmianie (jak dawniej users.role_id).
create table user_store_roles (
    id            uuid primary key,
    user_id       uuid        not null references users (id),
    store_id      uuid        not null references stores (id),
    store_role_id uuid        not null references store_roles (id),
    assigned_at   timestamptz not null,
    assigned_by   uuid references users (id)
);

create unique index user_store_roles_uq on user_store_roles (user_id, store_id);
create index user_store_roles_role_idx on user_store_roles (store_role_id);

-- Hierarchia przesłana sklepowi zaprzyjaźnionemu: treść (rangi z uprawnieniami) jako JSON z chwili wysłania,
-- a przy przyjęciu także poprzednia hierarchia odbiorcy, żeby dało się cofnąć.
create table store_role_offers (
    id               uuid primary key,
    from_store_id    uuid        not null references stores (id),
    to_store_id      uuid        not null references stores (id),
    payload          text        not null,
    previous_payload text,
    created_at       timestamptz not null,
    created_by       uuid references users (id),
    accepted_at      timestamptz,
    accepted_by      uuid references users (id),
    rejected_at      timestamptz,
    rejected_by      uuid references users (id),
    cancelled_at     timestamptz,
    cancelled_by     uuid references users (id),
    undone_at        timestamptz,
    undone_by        uuid references users (id)
);

create index store_role_offers_to_idx on store_role_offers (to_store_id, created_at desc);
create index store_role_offers_from_idx on store_role_offers (from_store_id, created_at desc);

-- Stanowisko wskazuje rangę kluczem, bo stanowiska są wspólne dla firmy, a rangi należą do sklepu.
alter table staff_positions add column role_key varchar(80);
-- role_id zostaje jako historia sprzed rang, nowe stanowiska mają tylko role_key (uzupełnia go StoreRoleBootstrap).
alter table staff_positions drop constraint staff_positions_role_check;
