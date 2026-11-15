-- Promocja, której zabrakło w nowszej liście promocji obejmującej jej datę końca, zakończyła się.
--
-- Lista (Taryfy) pokazuje bieżące i przyszłe promocje, więc promocja, której w niej nie ma, już nie trwa. Dotąd
-- zostawała w bazie z datą końca z listy, a raporty marż i ceny brały ją za trwającą (np. wcześniej odwołaną
-- promocję do 31.12). ended_on = dzień listy, w której jej zabrakło; ostatni dzień promocji to dzień przed nim.
-- effective_end to rzeczywisty koniec, z którego liczą raporty; end_date zostaje datą z listy (klucz promocji).

alter table promotions
    add column ended_on date,
    add column effective_end date generated always as (
        case when ended_on is not null and ended_on <= end_date then ended_on - 1 else end_date end) stored;

-- Dotychczasowe promocje: pierwsza późniejsza lista (bez wycofanych wgrań), która obejmowała datę końca, a promocji
-- w niej nie było.
update promotions p
set ended_on = e.day
from (select p2.id,
             min(coalesce(cast(n.report_generated_at as date),
                          cast(n.imported_at at time zone 'Europe/Warsaw' as date))) as day
      from promotions p2
      join promotion_imports l on l.id = p2.last_import_id
      join promotion_imports n on n.store_id = p2.store_id and n.imported_at > l.imported_at
                              and n.withdrawn_at is null
                              and n.end_from <= p2.end_date and p2.end_date <= n.end_to
      group by p2.id) e
where e.id = p.id;

create index promotions_store_effective_end_idx on promotions (store_id, effective_end);
