-- Artykuły z kartoteki, które w przeniesieniach stanów oznaczają gratis (np. "GRATIS SPOŻYWKA MIX"). Lista jest osobna
-- dla każdego sklepu (artykuł należy do sklepu). Taki artykuł z ilością na minusie robi z przeniesienia kandydata na
-- gratis, a na plusie to błąd do sprawdzenia. Usunięcie z listy to znacznik; ponowne dodanie to nowy wpis.
create table document_gratis_articles (
    id         uuid primary key,
    product_id uuid        not null references products (id),
    added_by   uuid references users (id) on delete set null,
    added_at   timestamptz not null,
    removed_by uuid references users (id) on delete set null,
    removed_at timestamptz
);

create unique index document_gratis_articles_uq on document_gratis_articles (product_id)
    where removed_at is null;

-- Start listy: artykuły, które dotąd wykrywała nazwa (pozycja GRATIS w przeniesieniach stanów sklepu).
insert into document_gratis_articles (id, product_id, added_at)
select gen_random_uuid(), found.id, now()
from (
    select distinct p.id
    from documents d
    join document_lines l on l.document_id = d.id
    join products p on p.store_id = d.store_id and p.itm8 = l.itm8
    where d.type = 'PPP' and upper(l.name) like '%GRATIS%'
) found;

-- Kandydat na gratis liczy się teraz z listy sklepu w chwili odczytu, a nie z nazwy przy imporcie.
alter table documents drop column has_gratis_line;
