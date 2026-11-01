-- Kartoteka dostawców (moduł Dostawcy). Dane z raportu "Lista dostawców" (kod, nazwa, adres) są per sklep, a to,
-- czego w raporcie nie ma - strona internetowa, ogólny telefon i e-mail, notatki, kontakty - jest wspólne dla firmy:
-- wszystkich sklepów z tym samym NIP-em (stores.nip). Profil łączy się z dostawcą sklepu po kluczu: kod dostawcy
-- z systemu sklepu, a u dodanego ręcznie NIP dostawcy albo nazwa. Import listy profili nie rusza.
create table supplier_profiles (
    id           uuid primary key default gen_random_uuid(),
    company_key  varchar(60)  not null, -- NIP firmy sklepu albo store:<id> u sklepu bez NIP-u
    supplier_key varchar(220) not null, -- code:<kod>, nip:<NIP> albo name:<nazwa małymi literami>
    website      varchar(500),
    email        varchar(200),
    phone        varchar(50),
    notes        text,
    created_at   timestamptz  not null default now(),
    updated_by   uuid references users (id) on delete set null,
    updated_at   timestamptz
);

create unique index supplier_profiles_key_uq on supplier_profiles (company_key, supplier_key);

-- Profil przypięty przy pierwszej zmianie, więc zmiana nazwy dostawcy dodanego ręcznie go nie odcina.
alter table suppliers add column profile_id uuid references supplier_profiles (id);

-- Osoba albo dział u dostawcy, wspólne dla sklepów firmy. Usunięcie to znacznik (toast "Przywróć").
create table supplier_contacts (
    id         uuid primary key default gen_random_uuid(),
    profile_id uuid        not null references supplier_profiles (id),
    kind       varchar(20) not null, -- SupplierContactKind
    name       varchar(150),
    job_title  varchar(100),
    phone      varchar(50),
    email      varchar(200),
    notes      text,
    created_by uuid references users (id) on delete set null,
    created_at timestamptz not null default now(),
    updated_by uuid references users (id) on delete set null,
    updated_at timestamptz,
    deleted_by uuid references users (id) on delete set null,
    deleted_at timestamptz
);

create index supplier_contacts_profile_idx on supplier_contacts (profile_id) where deleted_at is null;
