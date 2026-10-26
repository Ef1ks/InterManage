create table roles (
    id          uuid primary key default gen_random_uuid(),
    name        varchar(100) not null,
    description varchar(500),
    owner       boolean      not null default false,
    created_at  timestamptz  not null default now()
);

create unique index roles_name_uq on roles (lower(name));
-- Dokladnie jedna systemowa rola wlasciciela.
create unique index roles_single_owner_uq on roles (owner) where owner;

create table role_permissions (
    role_id  uuid        not null references roles (id) on delete cascade,
    category varchar(50) not null,
    level    varchar(20) not null,
    primary key (role_id, category)
);

create table users (
    id              uuid primary key default gen_random_uuid(),
    username        varchar(50)  not null,
    display_name    varchar(100) not null,
    password_hash   varchar(255) not null,
    role_id         uuid         not null references roles (id),
    enabled         boolean      not null default true,
    session_version integer      not null default 0,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now()
);

create unique index users_username_uq on users (lower(username));
create index users_role_id_idx on users (role_id);

insert into roles (name, description, owner)
values ('Właściciel', 'Pełny dostęp do wszystkich narzędzi i ustawień', true);
